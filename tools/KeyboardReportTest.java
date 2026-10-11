package com.slidepilot.app;

import java.util.Arrays;

/** Run with a JDK; checks the wire contract, independently of Bluetooth hardware. */
public final class KeyboardReportTest {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        int[] expectedUsages = {0x4e, 0x4b, 0x3e, 0x3e, 0x29, 0x05};
        int i = 0;
        for (KeyboardReport.Command command : KeyboardReport.Command.values()) {
            byte[] report = KeyboardReport.press(command);
            check(report.length == 8, "Boot keyboard report must be eight bytes");
            check((report[2] & 0xff) == expectedUsages[i++], "Incorrect PowerPoint key mapping");
            check(report[0] == (command == KeyboardReport.Command.CURRENT ? 2 : 0), "Incorrect modifier");
            check(report[1] == 0, "Reserved byte must be zero");
            for (int n = 3; n < 8; n++) check(report[n] == 0, "Unexpected held key");
        }
        byte[] release = KeyboardReport.release();
        check(Arrays.equals(release, new byte[8]), "Release must clear every key and modifier");
        release[0] = 2;
        check(KeyboardReport.release()[0] == 0, "Reports must not share mutable arrays");
        // Parse HID short items. Confirm advertised input/output lengths, not just byte identity.
        byte[] descriptor = KeyboardReport.descriptor();
        int size = 0, count = 0, inputBits = 0, outputBits = 0, collections = 0;
        for (int p = 0; p < descriptor.length;) {
            int prefix = descriptor[p++] & 255;
            check(prefix != 254, "Unexpected long item");
            int length = prefix & 3;
            if (length == 3) length = 4;
            check(p + length <= descriptor.length, "Truncated descriptor item");
            int value = 0;
            for (int n = 0; n < length; n++) value |= (descriptor[p++] & 255) << (8 * n);
            switch (prefix & 252) {
                case 0x74: size = value; break;
                case 0x94: count = value; break;
                case 0x84: throw new AssertionError("Descriptor must have no report ID");
                case 0x80: inputBits += size * count; break;
                case 0x90: outputBits += size * count; break;
                case 0xa0: collections++; break;
                case 0xc0: collections--; check(collections >= 0, "Unbalanced collection"); break;
                default: break;
            }
        }
        check(collections == 0, "Unclosed collection");
        check(inputBits == 64, "Descriptor input report must be 64 bits");
        check(outputBits == 8, "Descriptor LED report must be eight bits");
        System.out.println("PASS: six command mappings, modifiers, release isolation, descriptor structure and report lengths");
    }
}
