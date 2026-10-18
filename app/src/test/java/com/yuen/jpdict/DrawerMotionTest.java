package com.yuen.jpdict;

/** Deterministic trajectory checks; Android APIs are not invoked by this test. */
public final class DrawerMotionTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void near(float actual, float expected, float tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }
    private static DrawerMotionController.Spring trajectory(float hz, float target, float velocity) {
        DrawerMotionController.Spring spring = new DrawerMotionController.Spring();
        spring.target = target; spring.velocity = velocity;
        for (int i = 0; i < hz * 3 && spring.step(1 / hz); i++) {
            check(Float.isFinite(spring.position) && Float.isFinite(spring.velocity), "Finite trajectory");
            check(spring.position > -.1f && spring.position < 1.2f, "Bounded trajectory");
        }
        near(spring.position, target, .00001f, "Converges at " + hz + " Hz");
        near(spring.velocity, 0, .00001f, "Settled velocity");
        return spring;
    }
    public static void main(String[] args) {
        for (float hz : new float[] {30, 60, 90, 120, 144}) {
            for (float ratio : new float[] {0, .01f, .2f, .44f, .45f, .9f, 1}) trajectory(hz, ratio, .8f);
        }
        DrawerMotionController.Spring slow = new DrawerMotionController.Spring(), fast = new DrawerMotionController.Spring();
        slow.target = fast.target = 1; slow.velocity = fast.velocity = .8f;
        for (int i = 0; i < 15; i++) slow.step(1 / 60f);
        for (int i = 0; i < 30; i++) fast.step(1 / 120f);
        near(slow.position, fast.position, .01f, "Frame-rate independent displacement");
        near(slow.velocity, fast.velocity, .1f, "Frame-rate independent velocity");
        float before = slow.position, velocity = slow.velocity;
        slow.target = 0;
        near(slow.position, before, 0, "Reversal preserves current position");
        near(slow.velocity, velocity, 0, "Reversal preserves current velocity");
        slow.step(.35f);
        check(Float.isFinite(slow.position), "Long frame uses stable substeps");
        float previous = 1;
        for (int i = 0; i <= 100; i++) {
            float shown = DrawerMotionController.rubber(1 + i / 10f, 1);
            check(shown >= previous && shown < 1.05f, "Rubber band is monotonic and bounded"); previous = shown;
        }
        near(DrawerMotionController.rubber(-.4f, 1), -DrawerMotionController.rubber(.4f, 0), .000001f, "Both edges use the same resistance");
        near(DrawerMotionController.fullWidth(400, 1), 356, .001f, "Original phone width");
        near(DrawerMotionController.fullWidth(1200, 2), 760, .001f, "380dp large-screen cap");
        for (DrawerStyleSettings.Mode mode : DrawerStyleSettings.Mode.values()) {
            for (float ratio : new float[] {0, .01f, .2f, .44f, .45f, .9f, 1}) {
                DrawerMotionController.Geometry real = new DrawerMotionController.Geometry(), miniature = new DrawerMotionController.Geometry();
                real.update(mode, ratio, ratio, 356, 1);
                miniature.update(mode, ratio, ratio, 178, .5f);
                near(real.pageX / 2, miniature.pageX, .0001f, "Preview has the same displacement");
                near(real.menuX / 2, miniature.menuX, .0001f, "Preview has the same menu displacement");
                near(real.pageScale, miniature.pageScale, 0, "Preview depth matches");
                check(mode == DrawerStyleSettings.Mode.OVERLAY || real.shade == 0, "Only Overlay dims");
                if (mode == DrawerStyleSettings.Mode.OVERLAY) near(real.pageX, 0, 0, "Overlay home stays horizontally fixed");
                else near(real.pageX, 356 * ratio, .0001f, "No hidden 45% minimum or ignored full-page ratio");
            }
        }
        System.out.println("Drawer motion: " + checks + " trajectory and geometry checks passed");
    }
}
