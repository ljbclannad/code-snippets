class Solution {
    public boolean isSameTree(TreeNode p, TreeNode q) {
        if (p == null || q == null) {
            return p == q;
        }
        return p.val == q.val
                && isSameTree(p.left, q.left)
                && isSameTree(p.right, q.right);
    }

    public static void main(String[] args) {
        Solution solution = new Solution();

        check("相同的树", true, solution.isSameTree(
                new TreeNode(1, new TreeNode(2), new TreeNode(3)),
                new TreeNode(1, new TreeNode(2), new TreeNode(3))
        ));

        check("结构不同", false, solution.isSameTree(
                new TreeNode(1, new TreeNode(2), null),
                new TreeNode(1, null, new TreeNode(2))
        ));

        check("节点值不同", false, solution.isSameTree(
                new TreeNode(1, new TreeNode(2), new TreeNode(3)),
                new TreeNode(1, new TreeNode(2), new TreeNode(4))
        ));

        check("两棵空树", true, solution.isSameTree(null, null));

        check("只有一棵空树", false, solution.isSameTree(
                new TreeNode(1), null
        ));

        System.out.println("全部 5 个测试通过");
    }

    private static void check(String name, boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError(
                    name + "：期望 " + expected + "，实际 " + actual
            );
        }
        System.out.println("通过：" + name);
    }
}

class TreeNode {
    int val;
    TreeNode left;
    TreeNode right;

    TreeNode() {
    }

    TreeNode(int val) {
        this.val = val;
    }

    TreeNode(int val, TreeNode left, TreeNode right) {
        this.val = val;
        this.left = left;
        this.right = right;
    }
}
