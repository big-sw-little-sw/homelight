package io.github.bigswlittlesw.homelight.application;

/// Full-screen workflows in the HomeLight TUI.
public enum Screen {
    STATUS(1, "Status"),
    PLAN(2, "Plan"),
    APPLY(3, "Apply"),
    CONFIG(4, "Config");

    private final int number;
    private final String title;

    Screen(int number, String title) {
        this.number = number;
        this.title = title;
    }

    public int number() {
        return number;
    }

    public String title() {
        return title;
    }

    public String tabLabel() {
        return "[" + number + ": " + title + "]";
    }
}
