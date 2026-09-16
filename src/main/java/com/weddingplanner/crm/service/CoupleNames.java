package com.weddingplanner.crm.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a couple's inquiry name ("Amelia &amp; Noah") into the people it actually names.
 *
 * <p>A lead arrives addressed to a couple, because that is how a couple writes in. A contact card
 * is a person: it holds one phone, one email and one conversation identity. This is the seam
 * between the two — the inquiry keeps the couple's wording, the contacts it creates are
 * individuals.</p>
 *
 * <p>When only the last partner carries a family name ("Amelia &amp; Noah Rossi"), the earlier
 * single-token partners inherit it, which is what the writer meant.</p>
 */
public final class CoupleNames {

    private CoupleNames() {}

    /** Splits on the couple separators; returns the whole trimmed name when there is no couple. */
    public static List<String> split(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) return List.of();
        String[] parts = value.split("\\s*(?:&|\\+|\\band\\b)\\s*");
        var people = new ArrayList<String>();
        for (String part : parts) {
            String person = part.trim();
            if (!person.isEmpty()) people.add(person);
        }
        if (people.size() < 2) return List.of(value);
        String family = familyName(people.getLast());
        if (family != null) {
            for (int i = 0; i < people.size() - 1; i++) {
                if (!people.get(i).contains(" ")) people.set(i, people.get(i) + " " + family);
            }
        }
        return List.copyOf(people);
    }

    /** The trailing family name of a multi-word name, or null when the name is a single token. */
    private static String familyName(String person) {
        int space = person.lastIndexOf(' ');
        return space < 0 ? null : person.substring(space + 1);
    }
}
