package shaka.ts.migrator;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

import java.util.Arrays;

public final class ExternConversionPass implements CompilerPass {
    final AbstractCompiler compiler;
    private NameUtil nameUtil;

    public ExternConversionPass(AbstractCompiler compiler, NameUtil nameUtil) {
        this.compiler = compiler;
        this.nameUtil = nameUtil;
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(compiler, root, new Traversal());
    }

    private class Traversal extends AbstractPostOrderCallback {
        @Override
        public void visit(NodeTraversal t, Node n, Node parent) {
            System.out.println(n.toString());
            switch (n.getToken()) {
                case VAR:
                    // Rule: remove var initialized to empty object
                    if (n.getChildCount() == 1 && n.getFirstFirstChild() != null && n.getFirstFirstChild().isObjectLit()) {
                        var objectLiteral = n.getFirstFirstChild();
                        if (objectLiteral.getChildCount() == 0) {
                            n.detach();
                        }
                    }
                    break;
                case ASSIGN:
                    // Rule: remove namespace-like. For example 'muxjs.mp4 = {};'
                    if (n.getSecondChild().isObjectLit() && n.getSecondChild().getChildCount() == 0) {
                        n.getParent().detach();
                        return;
                    }
                    // Rule: convert any class expression to a class declaration (optionally with namespace)
                    if (n.getSecondChild().isClass() && n.getFirstChild().isGetProp()) {
                        var nameArray = getClassNameAndNamespace(n.getFirstChild());
                        var classNode = createClassNode(nameArray[1], n.getSecondChild(), n.getParent());
                        if (nameArray[0] != "") {
                            wrapInNamespace(classNode, nameArray[0]);
                        }
                    }

                    // Rule: wrap in a namespace any assignment expression with an object literal on the right side
                    if (n.getSecondChild() != null && n.getFirstChild().isGetProp()) {
                        var nameArray = getClassNameAndNamespace(n.getFirstChild());
                        n.replaceChild(first);
                        if (nameArray[0] != "") {
                            wrapInNamespace(n.getParent(), nameArray[0]);
                        }
                    }
                    break;
                case FUNCTION:
                    //Rule: replace empty body with ;
                    if (n.getParent().isMemberFunctionDef() && n.getLastChild().isBlock() && !n.getLastChild().hasChildren()) {
                        n.replaceChild(n.getLastChild(), new Node(Token.EMPTY));
                    }
                    break;
                case EXPR_RESULT:
                    //Rule: wrap structures like a.b.c; with a namespace a.b
                    if (!n.getFirstChild().isAssign()) {
                        var qualifiedName = n.getFirstChild().getQualifiedName();
                        var nameArray = getClassNameAndNamespace(n.getFirstChild());
                        if (nameArray[0] != "") {
                            wrapInNamespace(n.getFirstChild(), nameArray[0]);
                        }
                    }

            }
        }
    }

    private Node createClassNode(String className, Node classNode, Node classExpressionNode) {
        classNode.replaceChild(classNode.getFirstChild(), Node.newString(Token.NAME, className));
        classNode.detach();
        classExpressionNode.getParent().replaceChild(classExpressionNode, classNode);
        return classNode;
    }

    /**
     * Return the namespace and class from a class expression notation like a.b.c where
     * a.b is the namespace and c the class name.
     * @return Array where the first entry is the namespace and the second the class name
     */
    private String[] getClassNameAndNamespace(Node n) {
        var qualifiedName = n.getQualifiedName();
        var qualifiedNameParts = Arrays.asList(qualifiedName.split("\\."));
        if (qualifiedNameParts.size() > 1) {
            var namespace = String.join(".", qualifiedNameParts.subList(0, qualifiedNameParts.size() - 1));
            return new String[] { namespace, qualifiedNameParts.get(qualifiedNameParts.size() - 1) };
        } else {
            return new String[] { "", qualifiedName };
        }
    }

    private Node wrapInNamespace(Node bodyNode, String namespace) {
        var nameSpaceNode = new Node(Token.NAMESPACE, Node.newString(Token.NAME, namespace));
        var declareNode = new Node(Token.DECLARE, nameSpaceNode);
        var nameSpaceBody = new Node(Token.NAMESPACE_ELEMENTS);
        var bodyParent = bodyNode.getParent();
        bodyParent.replaceChild(bodyNode, declareNode);
        nameSpaceNode.addChildToBack(nameSpaceBody);
        nameSpaceBody.addChildToBack(bodyNode);
        return declareNode;
    }
}
