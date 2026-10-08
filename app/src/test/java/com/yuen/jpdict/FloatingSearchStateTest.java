package com.yuen.jpdict;

/** Standalone checks: compile with FloatingSearchState, then run this class's main. */
public final class FloatingSearchStateTest {
    public static void main(String[] args) {
        backClosesOnlyTheHighestLayer();
        repeatedMenuTapsAndSwitchesStayExclusive();
        privateModeClosesMenusAndPreservesOtherLayers();
        resetRemovesSessionState();
        nullMenusCannotCorruptState();
        System.out.println("FloatingSearchState: all interaction checks passed");
    }

    private static void backClosesOnlyTheHighestLayer() {
        for (boolean privateMode : new boolean[] { false, true }) {
            for (boolean drawerOpen : new boolean[] { false, true }) {
                for (boolean keyboardVisible : new boolean[] { false, true }) {
                    for (FloatingSearchState.Menu menu : FloatingSearchState.Menu.values()) {
                        FloatingSearchState state = new FloatingSearchState();
                        state.setPrivateMode(privateMode);
                        state.setDrawerOpen(drawerOpen);
                        state.setKeyboardVisible(keyboardVisible);
                        state.setMenu(menu);

                        if (menu != FloatingSearchState.Menu.NONE) {
                            equal(FloatingSearchState.BackAction.CLOSE_MENU, state.onBack());
                            equal(FloatingSearchState.Menu.NONE, state.getMenu());
                            equal(keyboardVisible, state.isKeyboardVisible());
                            equal(drawerOpen, state.isDrawerOpen());
                        }
                        if (keyboardVisible) {
                            equal(FloatingSearchState.BackAction.HIDE_KEYBOARD, state.onBack());
                            equal(false, state.isKeyboardVisible());
                            equal(drawerOpen, state.isDrawerOpen());
                        }
                        if (drawerOpen) {
                            equal(FloatingSearchState.BackAction.CLOSE_DRAWER, state.onBack());
                            equal(false, state.isDrawerOpen());
                        }
                        equal(FloatingSearchState.BackAction.CLOSE_SEARCH, state.onBack());
                        equal(FloatingSearchState.BackAction.CLOSE_SEARCH, state.onBack());
                        equal(privateMode, state.isPrivateMode());
                    }
                }
            }
        }
    }

    private static void repeatedMenuTapsAndSwitchesStayExclusive() {
        FloatingSearchState state = new FloatingSearchState();
        equal(FloatingSearchState.Menu.MODEL, state.toggleMenu(FloatingSearchState.Menu.MODEL));
        equal(FloatingSearchState.Menu.NONE, state.toggleMenu(FloatingSearchState.Menu.MODEL));
        state.toggleMenu(FloatingSearchState.Menu.MODEL);
        equal(FloatingSearchState.Menu.ATTACHMENT,
                state.toggleMenu(FloatingSearchState.Menu.ATTACHMENT));
        equal(FloatingSearchState.Menu.ATTACHMENT, state.getMenu());
        equal(FloatingSearchState.Menu.NONE, state.toggleMenu(FloatingSearchState.Menu.ATTACHMENT));
        state.setMenu(FloatingSearchState.Menu.ATTACHMENT);
        equal(FloatingSearchState.Menu.MODEL, state.toggleMenu(FloatingSearchState.Menu.MODEL));
        equal(FloatingSearchState.Menu.NONE, state.toggleMenu(FloatingSearchState.Menu.NONE));
        equal(FloatingSearchState.BackAction.CLOSE_SEARCH, state.onBack());
    }

    private static void privateModeClosesMenusAndPreservesOtherLayers() {
        FloatingSearchState state = new FloatingSearchState();
        state.setDrawerOpen(true);
        state.setKeyboardVisible(true);
        state.setMenu(FloatingSearchState.Menu.MODEL);
        equal(true, state.togglePrivateMode());
        equal(FloatingSearchState.Menu.NONE, state.getMenu());
        equal(true, state.isDrawerOpen());
        equal(true, state.isKeyboardVisible());
        state.setMenu(FloatingSearchState.Menu.ATTACHMENT);
        equal(false, state.togglePrivateMode());
        equal(FloatingSearchState.Menu.NONE, state.getMenu());
        equal(true, state.isDrawerOpen());
        equal(true, state.isKeyboardVisible());
        equal(FloatingSearchState.BackAction.HIDE_KEYBOARD, state.onBack());
        equal(FloatingSearchState.BackAction.CLOSE_DRAWER, state.onBack());
        equal(FloatingSearchState.BackAction.CLOSE_SEARCH, state.onBack());
    }

    private static void resetRemovesSessionState() {
        FloatingSearchState state = new FloatingSearchState();
        state.setPrivateMode(true);
        state.setDrawerOpen(true);
        state.setKeyboardVisible(true);
        state.setMenu(FloatingSearchState.Menu.ATTACHMENT);
        state.reset();
        equal(false, state.isPrivateMode());
        equal(false, state.isDrawerOpen());
        equal(false, state.isKeyboardVisible());
        equal(FloatingSearchState.Menu.NONE, state.getMenu());
        equal(FloatingSearchState.BackAction.CLOSE_SEARCH, state.onBack());
        state.reset();
        equal(FloatingSearchState.Menu.MODEL, state.toggleMenu(FloatingSearchState.Menu.MODEL));
    }

    private static void nullMenusCannotCorruptState() {
        FloatingSearchState state = new FloatingSearchState();
        state.setMenu(FloatingSearchState.Menu.MODEL);
        try {
            state.setMenu(null);
            throw new AssertionError("setMenu accepted null");
        } catch (IllegalArgumentException expected) {
            equal(FloatingSearchState.Menu.MODEL, state.getMenu());
        }
        try {
            state.toggleMenu(null);
            throw new AssertionError("toggleMenu accepted null");
        } catch (IllegalArgumentException expected) {
            equal(FloatingSearchState.Menu.MODEL, state.getMenu());
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
