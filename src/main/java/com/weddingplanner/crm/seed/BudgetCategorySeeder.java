package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.events.domain.BudgetArticle;
import com.weddingplanner.crm.events.domain.BudgetCategory;
import com.weddingplanner.crm.events.repository.BudgetArticleRepository;
import com.weddingplanner.crm.events.repository.BudgetCategoryRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.types.Ref;

/**
 * File the estimate's articles under what they are actually for.
 *
 * <p>The articles were imported from venue workbooks, and the importer had nothing better to file
 * them under than the day they fall on — so every article carries a category of {@code WEDDING},
 * {@code WELCOME} or {@code BRUNCH}. The breakdown on the event page groups by category and is
 * therefore, today, a second copy of the grouping by day: four rows, each holding everything.
 *
 * <p>"Where did the money go" is not answered by "the wedding day". It is answered by catering,
 * venue, flowers, music — the headings a planner argues about with a couple and negotiates with
 * suppliers. This reads each article's own wording and files it under one of those.
 *
 * <p>Only articles still filed under a day are touched. A category someone chose by hand is left
 * exactly as it is, which is also what makes a re-run a no-op.
 */
@Order(28)
@Component
@ConditionalOnProperty(name = "planner.events.demo", havingValue = "true")
public class BudgetCategorySeeder implements CommandLineRunner {

    /** First match wins, so the narrow wording comes before the words that also appear inside it. */
    private record Rule(String category, List<String> keywords) {}

    private static final List<Rule> RULES = List.of(
            new Rule("Venue & accommodation", List.of("venue", "villa", "rent of", "location fee", "estate",
                    "garden", "room", "accommodation", "suite", "terrace")),
            new Rule("Catering & bar", List.of("catering", "food", "menu", "dinner", "lunch", "breakfast",
                    "aperitif", "bar", "drink", "wine", "champagne", "coffee", "waiter", "chef",
                    "banquet", "snack", "brunch")),
            new Rule("Cake & desserts", List.of("cake", "dessert", "sweet", "patisserie", "confection")),
            new Rule("Florals & décor", List.of("flower", "floral", "bouquet", "decor", "décor", "centrepiece",
                    "installation", "candle", "linen", "tableware", "china", "glassware", "design")),
            new Rule("Photo & film", List.of("photo", "video", "film", "drone", "album", "shooter")),
            new Rule("Music & entertainment", List.of("band", "music", "dj", "entertain", "performer",
                    "firework", "show", "live set")),
            new Rule("Production & lighting", List.of("light", "sound", "stage", "production", "power",
                    "generator", "marquee", "tent", "dance floor", "furniture", "rental")),
            new Rule("Beauty & styling", List.of("hair", "make-up", "makeup", "beauty", "stylist", "dress",
                    "suit", "fitting")),
            // "stationar" on purpose: the imported workbooks spell it "stationary" more often than not.
            new Rule("Stationery & print", List.of("stationery", "stationar", "invitation", "print", "signage",
                    "calligraph", "menu card", "place card")),
            new Rule("Gifts & favours", List.of("gift", "favour", "welcome bag", "welcome pack", "hamper")),
            new Rule("Transport", List.of("transport", "transfer", "coach", "bus", "car", "shuttle", "boat",
                    "helicopter", "parking")),
            new Rule("Ceremony", List.of("ceremony", "celebrant", "officiant", "legal", "registrar", "church",
                    "permit", "licence")),
            new Rule("Planning fee", List.of("planning fee", "agency fee", "coordination", "management fee",
                    "planner")));

    /**
     * Where an article goes when its wording matches nothing. Left under the day it falls on it
     * would read as a category — a row called "Wedding" sitting between Catering and Florals looks
     * like a heading rather than the leftovers of an import.
     */
    private static final String FALLBACK = "Other";

    /** What the importer filed articles under, and the only categories this seeder will overwrite. */
    private static final List<String> DAYS = List.of("wedding", "welcome", "brunch", "planning",
            "uncategorised", "");

    private final BudgetArticleRepository articles;
    private final BudgetCategoryRepository categories;

    public BudgetCategorySeeder(BudgetArticleRepository articles, BudgetCategoryRepository categories) {
        this.articles = articles;
        this.categories = categories;
    }

    @Override
    public void run(String... args) {
        for (BudgetArticle article : articles.findAllActive()) {
            if (!refilable(article)) continue;
            String name = article.getDescription() == null ? "" : article.getDescription().toLowerCase(Locale.ROOT);
            if (name.isBlank()) continue;
            String filed = RULES.stream()
                    .filter(rule -> rule.keywords().stream().anyMatch(name::contains))
                    .map(Rule::category)
                    .findFirst()
                    .orElse(FALLBACK);
            article.setBudgetCategory(Ref.of(BudgetCategory.class, category(filed).getId()));
            articles.save(article);
        }
    }

    /**
     * An article is refilable while it is still filed under the day it falls on. Anything else is
     * somebody's decision — including one made by an earlier run of this seeder, which is what stops
     * a second boot from arguing with the first.
     *
     * <p>The day names reach an article two ways. An older import left them as the legacy text; the
     * {@code 2026.09.10.1} migration then turned that text into a category record, so most articles
     * now point at a catalog row <em>named</em> "Wedding". Reading only the legacy column would find
     * nothing to refile and quietly leave the breakdown as four rows, so the name behind the
     * reference is what gets tested.
     */
    private boolean refilable(BudgetArticle article) {
        return DAYS.contains(currentName(article).toLowerCase(Locale.ROOT).trim());
    }

    private String currentName(BudgetArticle article) {
        if (article.getBudgetCategory() != null) {
            return categories.findActiveById(article.getBudgetCategory().id())
                    .map(BudgetCategory::getDescription).orElse("");
        }
        return article.getCategory() == null ? "" : article.getCategory();
    }

    private BudgetCategory category(String name) {
        UUID id = UUID.nameUUIDFromBytes(("planner:budget-category:v1:" + name).getBytes(StandardCharsets.UTF_8));
        return categories.findActiveById(id).orElseGet(() -> {
            var category = new BudgetCategory();
            category.setId(id);
            category.setDescription(name);
            return categories.save(category);
        });
    }
}
