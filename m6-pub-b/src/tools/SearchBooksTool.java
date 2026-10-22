import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * search_books: targeted lookup by author and year range.
 *
 * Expected argument format: {@code author|year_from|year_to}
 * where empty parts mean "no constraint".  Example:
 * <pre>
 *   search_books("Ursula K. Le Guin|1970|1979")
 * </pre>
 * Any part may be left empty, e.g. {@code "|1990|2000"} for a
 * year-range query across all authors.  The author match is loose (see
 * {@link #authorMatches}) so "Ursula Le Guin" still finds "Ursula K. Le Guin".
 *
 * books.json keeps one book object per line, so we filter line by line
 * with a couple of regexes instead of pulling in a JSON library.
 */
public class SearchBooksTool implements Tool {

    private static final Pattern BOOK = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern AUTHOR = Pattern.compile("\"author\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern YEAR = Pattern.compile("\"year\"\\s*:\\s*(-?\\d+)");

    private final Path catalogue;

    public SearchBooksTool() {
        this(Path.of("books.json"));
    }

    public SearchBooksTool(Path catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public String name() {
        return "search_books";
    }

    @Override
    public String call(String arg) {
        String[] parts = (arg == null ? "" : arg).split("\\|", -1);
        String author = part(parts, 0);
        Integer from;
        Integer to;
        try {
            from = parseYear(part(parts, 1));
            to = parseYear(part(parts, 2));
        } catch (NumberFormatException e) {
            return "ERROR: bad year in '" + arg + "'; expected author|year_from|year_to";
        }

        String json;
        try {
            json = Files.readString(catalogue);
        } catch (IOException e) {
            return "ERROR: could not read " + catalogue + ": " + e.getMessage();
        }

        List<String> matches = new ArrayList<>();
        Matcher book = BOOK.matcher(json);
        while (book.find()) {
            String entry = book.group();
            Matcher a = AUTHOR.matcher(entry);
            Matcher y = YEAR.matcher(entry);
            if (!a.find() || !y.find()) {
                continue;
            }
            int year = Integer.parseInt(y.group(1));
            if (!author.isEmpty() && !authorMatches(a.group(1), author)) {
                continue;
            }
            if (from != null && year < from) {
                continue;
            }
            if (to != null && year > to) {
                continue;
            }
            matches.add(entry);
        }
        return "[" + String.join(", ", matches) + "]";
    }

    /**
     * Loose author match: every word of the query must appear as a word of
     * the catalogue author, ignoring case, punctuation and initials.  So
     * "Ursula Le Guin", "le guin" and "Guin" all match "Ursula K. Le Guin".
     */
    static boolean authorMatches(String catalogueAuthor, String query) {
        Set<String> have = new HashSet<>(words(catalogueAuthor));
        List<String> want = words(query);
        return !want.isEmpty() && have.containsAll(want);
    }

    /** Lowercased words with punctuation stripped; single letters (initials) dropped unless that leaves nothing. */
    private static List<String> words(String s) {
        List<String> all = new ArrayList<>();
        for (String w : s.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) {
                all.add(w);
            }
        }
        List<String> noInitials = new ArrayList<>();
        for (String w : all) {
            if (w.length() > 1) {
                noInitials.add(w);
            }
        }
        return noInitials.isEmpty() ? all : noInitials;
    }

    private static String part(String[] parts, int i) {
        return i < parts.length ? parts[i].trim() : "";
    }

    private static Integer parseYear(String s) {
        return s.isEmpty() ? null : Integer.valueOf(s);
    }
}
