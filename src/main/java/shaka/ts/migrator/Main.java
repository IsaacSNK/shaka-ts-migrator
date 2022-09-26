package shaka.ts.migrator;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.javascript.jscomp.SourceFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Main {
    public static void main(String[] args) {
        try {
            String shakaFolderPath = System.getProperty("user.home") + "/dev-workspace/shaka-player-fork";
            process(false, shakaFolderPath + "/lib", shakaFolderPath + "/ui");
            process(true,shakaFolderPath + "/externs");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void process(boolean declareOnly, String... paths) throws IOException {
        var gents = new TypeScriptGenerator(new Options());
        Set<Path> inputFiles = getAllFilesRecursively(paths);

        Set<String> filesToConvert = new HashSet<>();
        List<SourceFile> sourceFiles = new ArrayList<>();
        Map<String, File> inputFileMap = new HashMap<>();
        for (Path path : inputFiles) {
            File fileFromPath = path.toFile();
            String fileName = fileFromPath.getName();
            if (!filesToConvert.contains(fileName)) {
                filesToConvert.add(fileName);
                sourceFiles.add(SourceFile.fromCode(fileName, getFileText(fileFromPath)));
                inputFileMap.put(fileName.replace(".js", ""), fileFromPath);
            }
        }
        var gentsResult = gents.generateTypeScript(filesToConvert, sourceFiles, Collections.emptyList(), declareOnly);
        writeConvertedFiles(gentsResult.sourceFileMap, inputFileMap);
    }

    private static Set<Path> getAllFilesRecursively(String[] paths) throws IOException {
        Set<Path> result = new HashSet<>();
        BiPredicate<Path, BasicFileAttributes> matcher = (path, attributes) -> {
            return attributes.isRegularFile() && path.toString().endsWith(".js");
        };
        for (String path : paths) {
            try (Stream<Path> stream = java.nio.file.Files.find(Paths.get(path), 999, matcher)) {
                result.addAll(stream.collect(Collectors.toSet()));
            }
        }
        return result;
    }

    private static void writeConvertedFiles(Map<String, String> resultMap, Map<String, File> inputFileMap) {
        for (Map.Entry<String, String> entry : resultMap.entrySet()) {
            File jsFile = inputFileMap.get(entry.getKey());
            File output = new File(jsFile.getParentFile() + "/" + entry.getKey() + ".ts");
            try {
                Files.asCharSink(output, UTF_8).write(entry.getValue());
            } catch (IOException e) {
                throw new IllegalArgumentException("Unable to write to file " + output.getName(), e);
            }
        }
    }

    private static String getFileText(final File input) throws IOException {
        String fileContent = Files.asCharSource(input, Charsets.UTF_8).read();
        if (input.getPath().contains("externs")) {
            fileContent = fileContent.replaceAll("@externs", "");
        }
        if (input.getName().contains("mux")) {
            fileContent = fileContent.replaceAll("mp4: typeof muxjs\\.mp4", "")
                    .replaceAll("@constructor", "");
        }
        return fileContent;
    }
}
