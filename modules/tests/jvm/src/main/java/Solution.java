import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Solution {

    public static void main(String args[] ) throws Exception {
        /* Enter your code here. Read input from STDIN. Print output to STDOUT */
        // (B,D) (D,E) (A,B) (C,F) (E,G) (A,C)

        // Read input as string.
        Scanner in = new Scanner(System.in);
        String inputStr = in.nextLine();
        in.close();

        // Build and print the s-expression. Catch TreeErrors.
        try {
            System.out.println(buildSExpr(inputStr));
        } catch (TreeError e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * This method is responsible for building an s-expression out of well formatted
     * input, or throwing a relevant TreeError.
     */
    private static String buildSExpr(String inputStr) throws TreeError {
        // Parse input into array of [parent,child] pairs.
        char[][] input = parseInput(inputStr);

        // Map of Nodes keyed by their character value.
        Map<Character, Node> nodes = new HashMap<>();

        // For each char pair create new nodes if necessary.
        // Then, add the edge using Node#addChild
        for (char[] pair : input) {
            if (!nodes.containsKey(pair[0])) {
                nodes.put(pair[0], new Node(pair[0]));
            }
            if (!nodes.containsKey(pair[1])) {
                nodes.put(pair[1], new Node(pair[1]));
            }
            nodes.get(pair[0]).addChild(nodes.get(pair[1]));
        }

        // Find the root node. Throw TreeError(E4) if more than one.
        Node root = null;
        for (Node node : nodes.values()) {
            if (node.parent == null) {
                if (root == null) {
                    root = node;
                } else {
                    throw new TreeError("E4");
                }
            }
        }

        return buildSExpr(root);
    }

    /**
     * Given a tree node, recursively builds an s-expression.
     */
    private static String buildSExpr(Node node) {
        String left = node.left == null ? "" : buildSExpr(node.left);
        String right = node.right == null ? "" : buildSExpr(node.right);
        return "(" + node.value + left + right + ")";
    }

    /**
     * Returns an array of character pairs representing the parsed input.
     * Throws TreeError(E1) if unable to parse.
     */
    private static char[][] parseInput(String input) throws TreeError {
        String[] stringItems = input.split(" ");
        char[][] parsedItems = new char[stringItems.length][2];
        // For every item such as "(A,B)", set the relevant char pair or throw.
        for (int i = 0; i < stringItems.length; i++) {
            Pattern pattern = Pattern.compile("^\\(([A-Z]),([A-Z])\\)$");
            Matcher matcher = pattern.matcher(stringItems[i]);
            if (!matcher.find()) {
                throw new TreeError("E1");
            }
            parsedItems[i][0] = matcher.group(1).charAt(0);
            parsedItems[i][1] = matcher.group(2).charAt(0);
        }
        return parsedItems;
    }

    static class Node {
        char value;
        Node left = null;
        Node right = null;
        Node parent = null;

        Node(char value) {
            this.value = value;
        }

        /**
         * Adds a node as a child of this one.
         * Ensures the following constraints:
         *  - The left child is the first one to be populated.
         *  - The left child is always smaller than the right.
         *  - The parent can't have more than 2 children, else throws TreeError(E3)
         *  - The node isn't already a child of the parent, else throws TreeError(E2)
         *  - The child doesn't already have a parent, else throws TreeError(E5)
         *  - Adding this child doesn't create a directed cycle, else throws TreeError(E5)
         */
        void addChild(Node child) throws TreeError {
            // Add to next available child variable, or throw if no room.
            if (left == null) {
                left = child;
            } else if (right == null) {
                // New child cannot be equal to the existing one.
                if (child.value == left.value) {
                    throw new TreeError("E2");
                }
                right = child;
            } else {
                throw new TreeError("E3");
            }

            // If child already has parent OR child is in the parent's ancestor set,
            // throw cycle error. The first condition checks for undirected cycles,
            // while the second checks for directed ones.
            if (child.parent != null || ancestors().contains(child)) {
                throw new TreeError("E5");
            }

            // Update the child's parent pointer to complete the edge.
            child.parent = this;

            // Ensure left < right
            if (right != null && left.value > right.value) {
                Node temp = right;
                right = left;
                left = temp;
            }
        }

        /**
         * Returns the set of ancestor nodes.
         */
        Set<Node> ancestors() throws TreeError {
            if (parent == null)
                return new HashSet<>();
            else {
                Set<Node> ancestorSet = parent.ancestors();
                ancestorSet.add(parent);
                return ancestorSet;
            }
        }
    }

    static class TreeError extends Exception {
        TreeError(String message) {
            super(message);
        }
    }
}