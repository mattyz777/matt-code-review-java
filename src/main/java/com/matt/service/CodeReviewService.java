package com.matt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

@Slf4j
@Service
public class CodeReviewService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String FIELD_FILE = "file";
    private static final String FIELD_CODE = "code";
    private static final String FIELD_DIFF = "diff";

    public String process(String repoPath, String targetBranch, String sourceBranch)
            throws IOException, GitAPIException {

        Repository repo = openExistingRepo(repoPath);
        try (Git git = new Git(repo)) {
            git.fetch().call();
            List<FileDiff> diffs = gitDiff(repo, targetBranch, sourceBranch);
            return prepareCodeForLLMReview(repoPath, diffs);
        }
    }


    private Repository openExistingRepo(String repoPath) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(new File(repoPath + "/.git"))
                .readEnvironment()
                .findGitDir()
                .build();
    }

    private List<FileDiff> gitDiff(Repository repo, String targetBranch, String sourceBranch) throws IOException {
        ObjectId oldTree = repo.resolve(targetBranch + "^{tree}");
        ObjectId newTree = repo.resolve(sourceBranch + "^{tree}");

        List<FileDiff> results = new ArrayList<>();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter df = new DiffFormatter(out)) {

            df.setRepository(repo);
            df.setContext(0); // only changed lines
            List<DiffEntry> entries = df.scan(oldTree, newTree);

            for (DiffEntry entry : entries) {
                out.reset();
                df.format(entry);
                String rawDiff = out.toString();

                ParsedDiff parsed = parseUnifiedDiff(rawDiff);
                if (parsed.isOnlyWhitespace()) {
                    log.info("Skipping only whitespace changes file '{}'", entry.getNewPath());
                    continue;
                }

                results.add(new FileDiff(entry.getNewPath(), parsed));
            }
        }
        return results;
    }


    /**
     * diff --git a/UserService.java b/UserService.java
     * index 123..456 100644
     * --- a/UserService.java
     * +++ b/UserService.java
     * @@ -10,6 +10,7 @@ public class UserService {
     *      public void create() {
     * +        log.info("start");
     *          save();
     *      }
     * }
     * ------------------------------------------------------
     * Added lines:   ["log.info(\"start\");"]
     * Removed lines: []
     * Context lines: ["public void create() {", "save();", "}"]
     *
     */
    private ParsedDiff parseUnifiedDiff(String diff) {
        List<CodeChangeBlock> blocks = new ArrayList<>();

        int oldLine = 0;
        int newLine = 0;
        CodeChangeBlock current = null;

        for (String line : diff.split("\n")) {
            if (line.startsWith("@@")) {
                // @@ -10,7 +10,8 @@
                String[] parts = line.split(" ");
                oldLine = Integer.parseInt(parts[1].substring(1).split(",")[0]);
                newLine = Integer.parseInt(parts[2].substring(1).split(",")[0]);
                current = new CodeChangeBlock();
                blocks.add(current);
                continue;
            }

            if (current == null || line.startsWith("diff ") || line.startsWith("index ")
                    || line.startsWith("--- ") || line.startsWith("+++ ")
                    || line.startsWith("\\ No newline")) {
                continue;
            }

            if (line.startsWith("+")) {
                current.added.put(newLine++, line.substring(1));
            } else if (line.startsWith("-")) {
                current.removed.put(oldLine++, line.substring(1));
            } else {
                oldLine++;
                newLine++;
            }
        }

        return new ParsedDiff(blocks);
    }


    private String prepareCodeForLLMReview(String repoPath, List<FileDiff> diffs) throws IOException {
        List<Map<String, Object>> payload = new ArrayList<>();
        JavaParser parser = new JavaParser();

        for (FileDiff diff : diffs) {
            String path = repoPath + File.separator + diff.file;

            if (!diff.file.endsWith(".java")) {
                log.info("Skipping non-Java file: {}", diff.file);
                continue;
            }

            File file = new File(path);
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.getResult().isEmpty()) {
                throw new IOException("Failed to parse: " + path);
            }

            CompilationUnit cu = result.getResult().get();
            List<Map<String, Object>> codeBlocks = new ArrayList<>();

            // Changed methods
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (method.getRange().isEmpty()) continue;
                Range r = method.getRange().get();

                if (diff.parsed.touchesRange(r.begin.line, r.end.line)) {
                    codeBlocks.add(Map.of(
                            "type", "method",
                            "name", method.getNameAsString(),
                            "code", method.toString()
                    ));
                }
            }

            // Changed fields
            cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)
                    .forEach(field -> {
                        if (field.getRange().isEmpty()) return;
                        Range r = field.getRange().get();

                        if (diff.parsed.touchesRange(r.begin.line, r.end.line)) {
                            codeBlocks.add(Map.of(
                                    "type", "field",
                                    "code", field.toString()
                            ));
                        }
                    });

            // Changed imports
            cu.getImports().forEach(impt -> {
                if (impt.getRange().isEmpty()) return;
                Range r = impt.getRange().get();

                if (diff.parsed.touchesRange(r.begin.line, r.end.line)) {
                    codeBlocks.add(Map.of(
                            "type", "import",
                            "code", impt.toString()
                    ));
                }
            });

            // 4️⃣ Changed class-level annotations
            cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .forEach(clazz -> {
                        clazz.getAnnotations().forEach(anno -> {
                            if (anno.getRange().isEmpty()) return;
                            Range r = anno.getRange().get();

                            if (diff.parsed.touchesRange(r.begin.line, r.end.line)) {
                                codeBlocks.add(Map.of(
                                        "type", "class-annotation",
                                        "code", anno.toString()
                                ));
                            }
                        });
                    });

            if (!codeBlocks.isEmpty()) {
                payload.add(Map.of(
                        FIELD_FILE, diff.file,
                        FIELD_CODE, codeBlocks
                ));
            } else {
                log.info("Changes in '{}' are whitespace-only after filtering.", diff.file);
            }
        }

        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    static class FileDiff {
        final String file;
        final ParsedDiff parsed;

        FileDiff(String file, ParsedDiff parsed) {
            this.file = file;
            this.parsed = parsed;
        }
    }

    static class ParsedDiff {
        final List<CodeChangeBlock> blocks;

        ParsedDiff(List<CodeChangeBlock> blocks) {
            this.blocks = blocks;
        }

        boolean isOnlyWhitespace() {
            return blocks.stream().allMatch(CodeChangeBlock::isOnlyWhitespace);
        }

        boolean touchesRange(int start, int end) {
            return blocks.stream().anyMatch(h -> h.touches(start, end));
        }

        Map<String, Object> toMinimalDiff() {
            List<Map<String, Object>> hunksOut = new ArrayList<>();
            for (CodeChangeBlock h : blocks) {
                hunksOut.add(Map.of(
                        "added", h.nonEmptyAdded(),
                        "removed", h.nonEmptyRemoved()
                ));
            }
            return Map.of("hunks", hunksOut);
        }
    }

    static class CodeChangeBlock {
        final Map<Integer, String> added = new LinkedHashMap<>();
        final Map<Integer, String> removed = new LinkedHashMap<>();

        boolean touches(int start, int end) {
            return added.keySet().stream().anyMatch(l -> l >= start && l <= end)
                    || removed.keySet().stream().anyMatch(l -> l >= start && l <= end);
        }

        boolean isOnlyWhitespace() {
            return added.values().stream().allMatch(String::isBlank)
                    && removed.values().stream().allMatch(String::isBlank);
        }

        List<String> nonEmptyAdded() {
            return added.values().stream().filter(s -> !s.isBlank()).toList();
        }

        List<String> nonEmptyRemoved() {
            return removed.values().stream().filter(s -> !s.isBlank()).toList();
        }
    }
}
