package com.yuen.jpdict;

/** Search-layer transitions independent of Android views and animation timing. */
final class FloatingSearchState {
    enum Menu { NONE, MODEL, ATTACHMENT }
    enum BackAction { CLOSE_MENU, HIDE_KEYBOARD, CLOSE_DRAWER, CLOSE_SEARCH }

    private boolean privateMode;
    private boolean drawerOpen;
    private boolean keyboardVisible;
    private Menu menu = Menu.NONE;

    boolean isPrivateMode() { return privateMode; }
    void setPrivateMode(boolean value) { privateMode = value; }

    boolean isDrawerOpen() { return drawerOpen; }
    void setDrawerOpen(boolean value) { drawerOpen = value; }

    boolean isKeyboardVisible() { return keyboardVisible; }
    void setKeyboardVisible(boolean value) { keyboardVisible = value; }

    Menu getMenu() { return menu; }

    void setMenu(Menu value) {
        if (value == null) throw new IllegalArgumentException("Menu must not be null");
        menu = value;
    }

    Menu toggleMenu(Menu value) {
        if (value == null) throw new IllegalArgumentException("Menu must not be null");
        menu = menu == value ? Menu.NONE : value;
        return menu;
    }

    boolean togglePrivateMode() {
        privateMode = !privateMode;
        menu = Menu.NONE;
        return privateMode;
    }

    BackAction onBack() {
        if (menu != Menu.NONE) {
            menu = Menu.NONE;
            return BackAction.CLOSE_MENU;
        }
        if (keyboardVisible) {
            keyboardVisible = false;
            return BackAction.HIDE_KEYBOARD;
        }
        if (drawerOpen) {
            drawerOpen = false;
            return BackAction.CLOSE_DRAWER;
        }
        return BackAction.CLOSE_SEARCH;
    }

    void reset() {
        privateMode = false;
        drawerOpen = false;
        keyboardVisible = false;
        menu = Menu.NONE;
    }
}
