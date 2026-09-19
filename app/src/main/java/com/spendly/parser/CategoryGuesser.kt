package com.spendly.parser

/**
 * Guesses a category from a merchant name or notification text.
 *
 * This is only ever a first guess — [com.spendly.data.MerchantRule] records the
 * user's correction the first time they change one, and that always wins
 * afterwards. Keyword lists beat any clever scoring here because they are
 * obvious to read and trivial for the user to reason about when a guess is odd.
 */
object CategoryGuesser {

    /** Category names here must match [com.spendly.data.DefaultCategories]. */
    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "Food & Drink" to listOf(
            "restaurant", "cafe", "caffe", "coffee", "kopi", "kopitiam", "mamak",
            "starbucks", "mcdonald", "mcd", "kfc", "burger", "pizza", "subway",
            "bakery", "bake", "dessert", "boba", "tealive", "chatime", "zus",
            "food", "grabfood", "foodpanda", "deliveroo", "ubereats", "doordash",
            "dine", "bistro", "eatery", "canteen", "bar ", "pub", "brewery",
            "noodle", "ramen", "sushi", "nasi", "roti", "satay", "dimsum",
        ),
        "Groceries" to listOf(
            "grocer", "supermarket", "mart", "market", "tesco", "lotus",
            "aeon", "jaya", "village grocer", "giant", "speedmart", "mydin",
            "econsave", "ntuc", "fairprice", "cold storage", "sheng siong",
            "walmart", "costco", "aldi", "lidl", "sainsbury", "asda", "kroger",
            "7-eleven", "seven eleven", "family mart", "familymart", "99 speed",
        ),
        "Transport" to listOf(
            "grab", "uber", "lyft", "taxi", "cab", "mrt", "lrt", "ktm", "train",
            "bus", "rapid", "myrapid", "touch n go", "touchngo", "tng", "ez-link",
            "ezlink", "petrol", "petronas", "shell", "caltex", "esso", "bhp",
            "fuel", "gas station", "parking", "park", "toll", "plus highway",
            "airasia", "airline", "flight", "boarding", "aeroline",
        ),
        "Shopping" to listOf(
            "shopee", "lazada", "amazon", "taobao", "aliexpress", "temu", "zalora",
            "uniqlo", "zara", "h&m", "cotton on", "padini", "nike", "adidas",
            "ikea", "decathlon", "ace hardware", "mall", "store", "boutique",
            "apple store", "best buy", "harvey norman", "courts", "senheng",
        ),
        "Bills & Home" to listOf(
            "tnb", "tenaga", "electric", "syabas", "air selangor", "water",
            "indah water", "unifi", "maxis", "celcom", "digi", "umobile", "yes 4g",
            "astro", "singtel", "starhub", "m1", "telco", "broadband", "internet",
            "bill", "insurance", "takaful", "prudential", "aia", "great eastern",
            "rent", "rental", "maintenance", "utilities", "council", "assessment",
        ),
        "Fun" to listOf(
            "netflix", "spotify", "disney", "hbo", "viu", "iqiyi", "youtube",
            "cinema", "gsc", "tgv", "mbo", "cathay", "golden village",
            "steam", "playstation", "xbox", "nintendo", "epic games", "riot",
            "concert", "ticket", "karaoke", "bowling", "arcade", "theme park",
            "sunway lagoon", "legoland", "zoo", "museum",
        ),
        "Health" to listOf(
            "pharmacy", "farmasi", "guardian", "watsons", "caring", "big pharmacy",
            "clinic", "klinik", "hospital", "medical", "doctor", "dr ", "dental",
            "dentist", "optical", "specs", "lens", "gym", "fitness", "celebrity",
            "anytime fitness", "yoga", "physio", "wellness", "lab", "pathology",
        ),
        "Work" to listOf(
            "office", "stationery", "printing", "coworking", "wework",
            "linkedin", "zoom", "slack", "notion", "figma", "github",
            "aws", "google cloud", "azure", "digitalocean", "domain", "hosting",
            "openai", "anthropic", "adobe", "microsoft 365", "dropbox",
        ),
        "Gifts" to listOf(
            "gift", "florist", "flower", "hamper", "souvenir", "toy",
            "donation", "charity", "zakat", "masjid", "church", "temple",
        ),
    )

    /**
     * Returns the best-matching category *name*, or null when nothing matches.
     * The caller resolves the name to an id and falls back to
     * [com.spendly.data.DefaultCategories.FALLBACK] when this returns null.
     */
    fun guess(merchant: String?, rawText: String? = null): String? {
        val haystack = buildString {
            merchant?.let { append(it.lowercase()).append(' ') }
            rawText?.let { append(it.lowercase()) }
        }
        if (haystack.isBlank()) return null

        var best: String? = null
        var bestScore = 0

        for ((category, keywords) in KEYWORDS) {
            for (kw in keywords) {
                if (!haystack.contains(kw)) continue
                // Longer keywords are more specific, so they outrank short ones.
                // "village grocer" should beat a stray "grab" elsewhere in the text.
                var score = kw.length
                // A hit in the merchant name is worth far more than one anywhere
                // in the body, where bank boilerplate creates accidental matches.
                if (merchant != null && merchant.lowercase().contains(kw)) score += 20
                if (score > bestScore) {
                    bestScore = score
                    best = category
                }
            }
        }
        return best
    }
}
