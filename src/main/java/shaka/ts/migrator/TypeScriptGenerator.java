package shaka.ts.migrator;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.CodePrinter.Builder.CodeGeneratorFactory;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CodePrinter.Format;
import com.google.javascript.rhino.Node;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tool that transpiles {@code .js} ES6 and ES5 Closure annotated JavaScript to {@code .ts}
 * TypeScript.
 */
public class TypeScriptGenerator {
  private static final DiagnosticType GENTS_INTERNAL_ERROR =  DiagnosticType.error("INTERNAL_ERROR", "TS migrator failed: {0}");

  private final Options opts;
  private final Compiler compiler;
  final PathUtil pathUtil;
  private final NameUtil nameUtil;
  private GentsErrorManager errorManager;

  TypeScriptGenerator(Options opts) {
    this.opts = opts;
    this.compiler = new Compiler();
    compiler.disableThreads();
    setErrorStream(System.err);

    this.pathUtil = new PathUtil(opts.root, opts.absolutePathPrefix);
    this.nameUtil = new NameUtil(compiler);
  }

  void setErrorStream(PrintStream errStream) {
    errorManager =
        new GentsErrorManager(
            errStream, ErrorFormat.MULTILINE.toFormatter(compiler, true), opts.debug);
    compiler.setErrorManager(errorManager);
  }

  /** Returns a map from the basename to the TypeScript code generated for the file. */
  public GentsResult generateTypeScript(
      Set<String> filesToConvert, List<SourceFile> srcFiles, List<SourceFile> externs)
      throws AssertionError {
    GentsResult result = new GentsResult();

    final CompilerOptions compilerOpts = opts.getCompilerOptions();
    compiler.compile(externs, srcFiles, compilerOpts);

    Node externRoot = compiler.getRoot().getFirstChild();
    Node srcRoot = compiler.getRoot().getLastChild();

    new RemoveGoogScopePass(compiler).process(externRoot, srcRoot);

    CollectModuleMetadata modulePrePass = new CollectModuleMetadata(compiler, nameUtil, filesToConvert);
    modulePrePass.process(externRoot, srcRoot);

    // Strips all file nodes that we are not compiling.
    stripNonCompiledNodes(srcRoot, filesToConvert);

    CommentLinkingPass commentsPass = new CommentLinkingPass(compiler);
    commentsPass.process(externRoot, srcRoot);
    final NodeComments comments = commentsPass.getComments();

    NamespaceConversionPass modulePass =
        new NamespaceConversionPass(
            compiler,
            pathUtil,
            nameUtil,
            modulePrePass.getFileMap(),
            modulePrePass.getNamespaceMap(),
            comments,
            opts.alreadyConvertedPrefix);
    modulePass.process(externRoot, srcRoot);

    new TypeConversionPass(compiler, modulePrePass, comments).process(externRoot, srcRoot);

    new TypeAnnotationPass(
            compiler,
            pathUtil,
            nameUtil,
            modulePrePass.getSymbolMap(),
            modulePass.getTypeRewrite(),
            comments,
            opts.externsMap)
        .process(externRoot, srcRoot);

    new StyleFixPass(compiler, comments).process(externRoot, srcRoot);

    // We only use the source root as the extern root is ignored for codegen
    for (Node file : srcRoot.children()) {
      try {
        String filepath = pathUtil.getFilePathWithoutExtension(file.getSourceFileName());
        CodeGeneratorFactory factory =
            new CodeGeneratorFactory() {
              @Override
              public CodeGenerator getCodeGenerator(Format outputFormat, CodeConsumer cc) {
                return new GentsCodeGenerator(cc, compilerOpts, comments, opts.externsMap);
              }
            };

        String tsCode =
            new CodePrinter.Builder(file)
                .setCompilerOptions(opts.getCompilerOptions())
                .setTypeRegistry(compiler.getTypeRegistry())
                .setCodeGeneratorFactory(factory)
                .setPrettyPrint(true)
                .setLineBreak(true)
                .setOutputTypes(true)
                .build();

        // For whatever reason closure sometimes prefixes the emit with an empty new line. Strip
        // newlines not present in the original source.
        CharSequence originalSourceCode =
            compiler.getSourceFileContentByName(file.getSourceFileName());

        Integer originalCount = countBeginningNewlines(originalSourceCode);
        Integer newCount = countBeginningNewlines(tsCode);

        if (newCount > originalCount) {
          tsCode = tsCode.substring(newCount - originalCount);
        }

        result.sourceFileMap.put(filepath, tsCode);
      } catch (Throwable t) {
        System.err.println("Failed while converting " + file.getSourceFileName());
        t.printStackTrace(System.err);
        compiler.report(
            JSError.make(file.getSourceFileName(), -1, -1, GENTS_INTERNAL_ERROR, t.getMessage()));
      }
    }

    result.moduleRewriteLog =
        new ModuleRenameLogger()
            .generateModuleRewriteLog(filesToConvert, modulePrePass.getNamespaceMap());
    errorManager.doGenerateReport();
    return result;
  }

  private Integer countBeginningNewlines(CharSequence originalSourceCode) {
    Integer originalCount = 0;
    for (Integer i = 0; i < originalSourceCode.length(); i++) {
      // There's a terrible hack in GentsCodeGenerator that it sometimes adds " \n" instead of "\n".
      // Count and strip that too.
      if (originalSourceCode.charAt(i) == '\n'
          || (originalSourceCode.charAt(i) == ' '
              && i + 1 < originalSourceCode.length()
              && originalSourceCode.charAt(i + 1) == '\n')) {
        originalCount += 1;
      } else {
        break;
      }
    }
    return originalCount;
  }

  /** Removes the root nodes for all the library files from the source node. */
  private static void stripNonCompiledNodes(Node n, Set<String> filesToCompile) {
    for (Node child : n.children()) {
      if (!filesToCompile.contains(child.getSourceFileName())) {
        child.detach();
      }
    }
  }

  static class GentsResult {

    public Map<String, String> sourceFileMap = new LinkedHashMap<>();
    public String moduleRewriteLog = "";
  }
}
