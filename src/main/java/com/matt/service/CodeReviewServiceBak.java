package com.matt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * A Merge Request (MR) is:
 *      A source branch (feature-branch) → where changes are made
 *      A target branch (main) → where the MR is intended to be merged
 *
 * The MR represents the diff between these two branches.
 * The MR branch doesn’t have to be directly branched from the target.
 * git diff branchOne..branchTwo
 *
 * After merging the MR
 *      git checkout main
 *      git branch -d 20260202-mr-matt
 *      git fetch --prune   # updates local remote refs
 */
@Slf4j
@Service
public class CodeReviewServiceBak {

    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final static String FIELD_FILE = "file";
    private final static String FIELD_ADD = "add";
    private final static String FIELD_REMOVE = "remove";
    private final static String FIELD_CODE = "code";

    /**
     * @param repoPath "full path to the repo where the .git is located"
     * @param targetBranch "main" or "master"
     * @param sourceBranch "feature/mr-123"
     */
    public String process(String repoPath, String targetBranch, String sourceBranch) throws IOException, GitAPIException {
        Repository repo = openExistingRepo(repoPath);
        try (Git git = new Git(repo)) {
            fetchOrigin(git);

            checkoutBranch(git, targetBranch);
            checkoutBranch(git, sourceBranch);
            List<Map<String, Object>> fileChangesMap = gitDiffAsLLM(repo, targetBranch, sourceBranch);
            return prepareCodeForLLMReview(repoPath, fileChangesMap);
        }
    }

    private Repository openExistingRepo(String repoPath) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        return builder.setGitDir(new File(repoPath + "/.git"))
                .readEnvironment()
                .findGitDir()
                .build();
    }

    /**
     * Checkout branch from remote, create locally if it doesn't exist
     */
    private void checkoutBranch(Git git, String branch) throws GitAPIException, IOException {
        boolean branchExistsLocally = git.getRepository().findRef(branch) != null;

        if (branchExistsLocally) {
            git.checkout()
                    .setName(branch)
                    .call();
        } else {
            git.checkout()
                    .setName(branch)
                    .setCreateBranch(true)
                    .setStartPoint("origin/" + branch)
                    .call();
        }
    }

    /**
     * Fetch latest changes from remote origin
     */
    private void fetchOrigin(Git git) throws GitAPIException {
        git.fetch().call();
    }

    private String gitDiffAsJson(Repository repository, String targetBranch, String sourceBranch) throws IOException {
        ObjectId targetTree = repository.resolve(targetBranch + "^{tree}");
        ObjectId sourceTree = repository.resolve(sourceBranch + "^{tree}");

        List<Map<String, Object>> files = new ArrayList<>();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter df = new DiffFormatter(out)) {

            df.setRepository(repository);
            List<DiffEntry> diffs = df.scan(targetTree, sourceTree);

            for (DiffEntry diff : diffs) {
                df.format(diff);
                files.add(Map.of(
                        "file", diff.getNewPath(),
                        "diff", out.toString()
                ));
                out.reset();
            }
        }

        String result = OBJECT_MAPPER.writeValueAsString(files);
        log.info("------------");
        log.info(result);
        log.info("------------");
        return result;
    }

    /**
     * Optimize the output of getDiffAsJson for LLM code review.
     * - Only keep added and removed lines
     * - Remove raw diff markers like 'diff --git', 'index', '---', '+++', '\ No newline'
     */
    public List<Map<String, Object>> gitDiffAsLLM(Repository repo, String targetBranch, String sourceBranch) throws IOException {
        String originalJson = gitDiffAsJson(repo, targetBranch, sourceBranch);

        List<Map<String, String>> files = OBJECT_MAPPER.readValue(
                originalJson,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class)
        );

        List<Map<String, Object>> optimizedFiles = new ArrayList<>();
        for (Map<String, String> fileMap : files) {
            String diffText = fileMap.get("diff");
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();

            for (String line : diffText.split("\n")) {
                line = line.trim(); // remove leading/trailing whitespace

                if (line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("diff ") || line.startsWith("index ")) {
                    continue; // skip metadata
                }
                if (line.startsWith("+") && !line.startsWith("++")) {
                    String addedLine = line.substring(1).trim();
                    if (!addedLine.isEmpty()) {
                        added.add(addedLine);
                    }
                } else if (line.startsWith("-") && !line.startsWith("--")) {
                    String removedLine = line.substring(1).trim();
                    if (!removedLine.isEmpty()) {
                        removed.add(removedLine);
                    }
                }
            }

            if (!added.isEmpty() || !removed.isEmpty()) {
                Map<String, Object> optimizedFile = new HashMap<>();
                optimizedFile.put(FIELD_FILE, fileMap.get("file"));
                optimizedFile.put(FIELD_ADD, added);
                optimizedFile.put(FIELD_REMOVE, removed);
                optimizedFiles.add(optimizedFile);
            } else {
                log.info("File '{}' has only whitespace changes and is skipped for LLM review.", fileMap.get(FIELD_FILE));
            }
        }

        return optimizedFiles;
    }

    public String prepareCodeForLLMReview(String repoPath, List<Map<String, Object>> fileChangesMap) throws IOException {
        List<Map<String, Object>> reviewPayload = new ArrayList<>();
        JavaParser parser = new JavaParser();

        for (Map<String, Object> fileMap : fileChangesMap) {
            String filePath = repoPath + File.separator + fileMap.get(FIELD_FILE);
            File file = new File(filePath);

            List<String> addedLines = (List<String>) fileMap.get(FIELD_ADD);
            if (addedLines.isEmpty()) {
                continue;
            }

            // Only parse Java files
            if (!filePath.endsWith(".java")) {
                log.info("Skipping non-Java file parsing: {}", filePath);
                Map<String, Object> payloadEntry = Map.of(
                        FIELD_FILE, fileMap.get(FIELD_FILE),
                        FIELD_CODE, addedLines
                );
                reviewPayload.add(payloadEntry);
                continue;
            }

            ParseResult<CompilationUnit> result = parser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.error("Failed to parse Java file: {}", filePath);
                throw new IOException("Failed to parse Java file: " + filePath);
            }

            CompilationUnit cu = result.getResult().get();
            Map<String, String> methodCodeMap = new HashMap<>();

            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                String methodCode = method.toString();
                boolean containsChange = false;

                // Check if any added line exists inside this method
                for (String addedLine : addedLines) {
                    if (methodCode.contains(addedLine.trim())) {
                        containsChange = true;
                        break;
                    }
                }

                if (containsChange) {
                    methodCodeMap.put(method.getNameAsString(), methodCode);
                }
            }

            if (!methodCodeMap.isEmpty()) {
                // Send all changed method code to LLM
                Map<String, Object> payloadEntry = new HashMap<>();
                payloadEntry.put(FIELD_FILE, fileMap.get(FIELD_FILE));
                payloadEntry.put(FIELD_CODE, new ArrayList<>(methodCodeMap.values()));
                reviewPayload.add(payloadEntry);
            } else {
                log.info("Added lines not found in any method for file: {}", filePath);
            }
        }

        return OBJECT_MAPPER.writeValueAsString(reviewPayload);
    }

}
