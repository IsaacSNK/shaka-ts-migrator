package shaka.ts.migrator;

import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.CodeConsumer;
import com.google.javascript.jscomp.CodeGenerator;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.rhino.JSDocInfo.Visibility;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

import javax.annotation.Nullable;
import java.util.Map;

/** Code generator for gents to add TypeScript specific code generation. */
public class GentsCodeGenerator extends CodeGenerator {

  private final NodeComments nodeComments;
  private final Map<String, String> externsMap;

  GentsCodeGenerator(
      CodeConsumer consumer,
      CompilerOptions options,
      NodeComments nodeComments,
      Map<String, String> externsMap) {
    super(consumer, options);
    this.nodeComments = nodeComments;
    this.externsMap = externsMap;
  }

  @Override
  protected void add(Node n, Context ctx) {
    @Nullable Node parent = n.getParent();
    maybeAddNewline(n);

    String comment = nodeComments.getComment(n);
    if (comment != null) {
      // CodeGernator.add("\n") doesn't append anything. Fixing the actual bug in Closure Compiler
      // is difficult. Works around the bug by passing " \n". The extra whitespace is stripped by
      // Closure and not emitted in the final output of Gents. An exception is when this is the
      // first line of file Closure doesn't strip the whitespace. TypeScriptGenerator has the
      // handling logic that removes leading empty lines, including "\n" and " \n".
      add(" " + comment);
      add(" \n");
    }

    if (maybeOverrideCodeGen(n)) {
      return;
    }
    super.add(n, ctx);

    // Default field values
    switch (n.getToken()) {
      case MEMBER_VARIABLE_DEF:
        if (n.hasChildren()) {
          add(" = ");
          add(n.getLastChild());
        }
        break;
      case NEW:
        // The Closure Compiler code generator drops off the extra () for new statements.
        // We add them back in to maintain a consistent style.
        if (n.hasOneChild()) {
          add("()");
        }
        break;
      case FUNCTION_TYPE:
        // Match the "(" in maybeOverrideCodeGen for FUNCTION_TYPE nodes.
        if (parent != null && parent.getToken() == Token.UNION_TYPE) {
          add(")");
        }
        break;
      case NAMESPACE:
        add("\n");
        break;

      default:
        break;
    }
  }

  private static final ImmutableSet<Token> TOKENS_TO_ADD_NEWLINES_BEFORE =
      ImmutableSet.of(
          Token.CLASS, Token.EXPORT, Token.FUNCTION, Token.INTERFACE, Token.MEMBER_FUNCTION_DEF);

  /** Add newlines to the generated source. */
  private void maybeAddNewline(Node n) {
    boolean hasComment =
        nodeComments.hasComment(n)
            || nodeComments.hasComment(n.getParent())
            || isPreviousEmptyAndHasComment(n)
            || (n.getParent() != null && isPreviousEmptyAndHasComment(n.getParent()));

    if (!hasComment && TOKENS_TO_ADD_NEWLINES_BEFORE.contains(n.getToken())) {
      // CodeGernator.add("\n") doesn't append anything. Fixing the actual bug in Closure Compiler
      // is difficult. Works around the bug by passing " \n". The extra whitespace is stripped by
      // Closure and not emitted in the final output of Gents. An exception is when this is the
      // first line of file Closure doesn't strip the whitespace. TypeScriptGenerator has the
      // handling logic that removes leading empty lines, including "\n" and " \n".
      add(" \n");
    }
  }

  private boolean isPreviousEmptyAndHasComment(Node n) {
    if (n == null || n.getParent() == null) {
      return false;
    }
    Node prev = n.getPrevious();
    return prev != null && prev.isEmpty() && nodeComments.hasComment(prev);
  }

  /**
   * Attempts to seize control of code generation if necessary.
   *
   * @return true if no further code generation on this node is needed.
   */
  private boolean maybeOverrideCodeGen(Node n) {
    @Nullable Node parent = n.getParent();
    switch (n.getToken()) {
      case INDEX_SIGNATURE:
        Node first = n.getFirstChild();
        if (null != first) {
          add("{[");
          add(first);
          add(":");
          add(first.getDeclaredTypeExpression());
          add("]:");
          add(n.getDeclaredTypeExpression());
          add("}");
        }
        return true;
      case UNDEFINED_TYPE:
        add("undefined");
        return true;
      case CAST:
        add("(");
        add(n.getFirstChild());
        add(" as ");
        add(n.getDeclaredTypeExpression());
        add(")");
        return true;
      case DEFAULT_VALUE:
      case NAME:
        // Prepend access modifiers on constructor params
        if (n.getParent().isParamList()) {
          Visibility visibility = (Visibility) n.getProp(Node.ACCESS_MODIFIER);
          if (visibility != null) {
            switch (visibility) {
              case PRIVATE:
                add("private ");
                break;
              case PROTECTED:
                add("protected ");
                break;
              case PUBLIC:
                add("public ");
                break;
              default:
                break;
            }
          }

          if (n.getBooleanProp(Node.IS_CONSTANT_NAME)) {
            add("readonly ");
          }
        }
        return false;
      case ANY_TYPE:
        // Check the externsMap for an alias to use in place of "any"
        String anyTypeName = externsMap.get("any");
        if (anyTypeName != null) {
          add(anyTypeName);
          return true;
        }
        return false;
      case EXPORT:
        // When a type alias is exported, closure code generator will add two semi-colons, one for
        // type alias and one for export
        // For example: export type T = {key: string};;
        if (!n.hasOneChild()) {
          return false;
        }
        if (n.getFirstChild().getToken() == Token.TYPE_ALIAS) {
          add("export");
          add(n.getFirstChild());
          return true;
        }
        return false;
      case FUNCTION_TYPE:
        // In some cases we need to add a pair of "(" and ")" around the function type. We don't
        // want to override the default code generation for FUNCTION_TYPE because the default code
        // generation uses private APIs. Therefore we emit a "(" here, then let the default code
        // generation for FUNCTION_TYPE emit and finally emit a ")" after maybeOverrideCodeGen.
        // Union binding has higher precedence than "=>" in TypeScript.
        if (parent != null && parent.getToken() == Token.UNION_TYPE) {
          add("(");
        }
        return false;
      default:
        return false;
    }
  }
}
