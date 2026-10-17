package com.radiant.pcbrowser

/** Everything a controller button can be bound to. Ids must match the JS engine's KEYMETA table and actions. */
object BindTargets {

    data class Target(val id: String, val label: String)

    val ALL: List<Target> by lazy {
        val list = ArrayList<Target>()
        fun add(id: String, label: String) = list.add(Target(id, label))

        add("None", "Unbound")
        add("Mouse0", "Left click (fire)")
        add("Mouse2", "Right click (aim)")
        add("Mouse1", "Middle click")
        add("WheelUp", "Mouse wheel up (previous weapon)")
        add("WheelDown", "Mouse wheel down (next weapon)")
        add("Toggle12", "Toggle weapon 1 / 2")
        add("Menu", "Esc on tap, Enter on hold")
        add("KeyF+KeyE", "F + E (use / interact)")
        add("Space", "Space")
        add("Enter", "Enter")
        add("Escape", "Esc")
        add("Tab", "Tab")
        add("ShiftLeft", "Shift")
        add("ControlLeft", "Ctrl")
        add("AltLeft", "Alt")
        add("Backspace", "Backspace")
        add("CapsLock", "Caps Lock")
        for (c in 'A'..'Z') add("Key$c", c.toString())
        for (d in 0..9) add("Digit$d", d.toString())
        for (f in 1..12) add("F$f", "F$f")
        add("ArrowUp", "Arrow Up")
        add("ArrowDown", "Arrow Down")
        add("ArrowLeft", "Arrow Left")
        add("ArrowRight", "Arrow Right")
        add("Insert", "Insert")
        add("Delete", "Delete")
        add("Home", "Home")
        add("End", "End")
        add("PageUp", "Page Up")
        add("PageDown", "Page Down")
        add("Backquote", "` (console)")
        add("Minus", "-")
        add("Equal", "=")
        add("BracketLeft", "[")
        add("BracketRight", "]")
        add("Backslash", "\\")
        add("Semicolon", ";")
        add("Quote", "'")
        add("Comma", ",")
        add("Period", ".")
        add("Slash", "/")
        for (d in 0..9) add("Numpad$d", "Numpad $d")
        add("NumpadEnter", "Numpad Enter")
        list
    }

    fun label(id: String): String {
        ALL.firstOrNull { it.id == id }?.let { return it.label }
        return id.split('+').joinToString(" + ") { part ->
            ALL.firstOrNull { it.id == part }?.label ?: part
        }
    }
}
