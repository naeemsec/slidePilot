package com.presakey.app;

/** Composite HID device: Report ID 1 = Keyboard, Report ID 2 = Mouse/Touchpad. */
public final class KeyboardReport {
    private KeyboardReport() {}

    public enum Command {
        NEXT(0, 0x4e), PREVIOUS(0, 0x4b), START(0, 0x3e),
        CURRENT(0x02, 0x3e), END(0, 0x29), BLACK(0, 0x05),
        ENTER(0, 0x28),
        D1(0, 0x1e), D2(0, 0x1f), D3(0, 0x20), D4(0, 0x21), D5(0, 0x22),
        D6(0, 0x23), D7(0, 0x24), D8(0, 0x25), D9(0, 0x26), D0(0, 0x27);
        final int modifier, usage;
        Command(int modifier, int usage) { this.modifier = modifier; this.usage = usage; }
    }

    public static byte[] press(Command command) {
        return new byte[] {(byte) command.modifier, 0, (byte) command.usage, 0, 0, 0, 0, 0};
    }

    public static byte[] release() { return new byte[8]; }

    private static final Command[] DIGITS = {Command.D0, Command.D1, Command.D2, Command.D3, Command.D4,
            Command.D5, Command.D6, Command.D7, Command.D8, Command.D9};

    public static Command digit(int value) { return DIGITS[value]; }

    public static byte[] descriptor() {
        return new byte[] {
            // --- KEYBOARD (Report ID 1) ---
            0x05, 0x01,       // USAGE_PAGE (Generic Desktop)
            0x09, 0x06,       // USAGE (Keyboard)
            (byte) 0xa1, 0x01,// COLLECTION (Application)
            (byte) 0x85, 0x01,//   REPORT_ID (1)
            0x05, 0x07,       //   USAGE_PAGE (Keyboard)
            0x19, (byte) 0xe0,//   USAGE_MINIMUM (224)
            0x29, (byte) 0xe7,//   USAGE_MAXIMUM (231)
            0x15, 0x00,       //   LOGICAL_MINIMUM (0)
            0x25, 0x01,       //   LOGICAL_MAXIMUM (1)
            0x75, 0x01,       //   REPORT_SIZE (1)
            (byte) 0x95, 0x08,//   REPORT_COUNT (8)
            (byte) 0x81, 0x02,//   INPUT (Data,Var,Abs)
            (byte) 0x95, 0x01,//   REPORT_COUNT (1)
            0x75, 0x08,       //   REPORT_SIZE (8)
            (byte) 0x81, 0x01,//   INPUT (Cnst,Ary,Abs)
            (byte) 0x95, 0x05,//   REPORT_COUNT (5)
            0x75, 0x01,       //   REPORT_SIZE (1)
            0x05, 0x08,       //   USAGE_PAGE (LEDs)
            0x19, 0x01,       //   USAGE_MINIMUM (1)
            0x29, 0x05,       //   USAGE_MAXIMUM (5)
            (byte) 0x91, 0x02,//   OUTPUT (Data,Var,Abs)
            (byte) 0x95, 0x01,//   REPORT_COUNT (1)
            0x75, 0x03,       //   REPORT_SIZE (3)
            (byte) 0x91, 0x01,//   OUTPUT (Cnst,Ary,Abs)
            (byte) 0x95, 0x06,//   REPORT_COUNT (6)
            0x75, 0x08,       //   REPORT_SIZE (8)
            0x15, 0x00,       //   LOGICAL_MINIMUM (0)
            0x25, 0x65,       //   LOGICAL_MAXIMUM (101)
            0x05, 0x07,       //   USAGE_PAGE (Keyboard)
            0x19, 0x00,       //   USAGE_MINIMUM (0)
            0x29, 0x65,       //   USAGE_MAXIMUM (101)
            (byte) 0x81, 0x00,//   INPUT (Data,Ary,Abs)
            (byte) 0xc0,      // END_COLLECTION

            // --- MOUSE (Report ID 2) ---
            0x05, 0x01,       // USAGE_PAGE (Generic Desktop)
            0x09, 0x02,       // USAGE (Mouse)
            (byte) 0xa1, 0x01,// COLLECTION (Application)
            (byte) 0x85, 0x02,//   REPORT_ID (2)
            0x09, 0x01,       //   USAGE (Pointer)
            (byte) 0xa1, 0x00,//   COLLECTION (Physical)
            0x05, 0x09,       //     USAGE_PAGE (Button)
            0x19, 0x01,       //     USAGE_MINIMUM (Button 1)
            0x29, 0x03,       //     USAGE_MAXIMUM (Button 3)
            0x15, 0x00,       //     LOGICAL_MINIMUM (0)
            0x25, 0x01,       //     LOGICAL_MAXIMUM (1)
            0x75, 0x01,       //     REPORT_SIZE (1)
            (byte) 0x95, 0x03,//     REPORT_COUNT (3)
            (byte) 0x81, 0x02,//     INPUT (3 Buttons)
            0x75, 0x05,       //     REPORT_SIZE (5)
            (byte) 0x95, 0x01,//     REPORT_COUNT (1)
            (byte) 0x81, 0x03,//     INPUT (Padding)
            0x05, 0x01,       //     USAGE_PAGE (Generic Desktop)
            0x09, 0x30,       //     USAGE (X)
            0x09, 0x31,       //     USAGE (Y)
            0x09, 0x38,       //     USAGE (Wheel)
            (byte) 0x15, (byte) 0x81, // LOGICAL_MINIMUM (-127)
            0x25, 0x7f,       //     LOGICAL_MAXIMUM (127)
            0x75, 0x08,       //     REPORT_SIZE (8)
            (byte) 0x95, 0x03,//     REPORT_COUNT (3)
            (byte) 0x81, 0x06,//     INPUT (Data,Var,Rel)
            (byte) 0xc0,      //   END_COLLECTION
            (byte) 0xc0       // END_COLLECTION
        };
    }
}
