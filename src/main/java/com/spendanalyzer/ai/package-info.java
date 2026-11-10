/**
 * The hand-written spend classifier. Everything here is plain Java on the JDK: no AI service, no
 * machine-learning or language library, no pretrained files.
 *
 * <p>How a text such as "juse 2" becomes "Snacks &amp; Beverages, item juice":
 * <ol>
 * <li>{@code text}: clean up, split into words, expand short forms ("tkt"), mark fillers and
 * numbers.</li>
 * <li>{@code lexicon}: the hand-written word lists under {@code resources/ai} (about 1,500 items
 * with 10,000 spellings and phrases, Tanglish and Hinglish included) and the phrase matcher.</li>
 * <li>{@code fuzzy}: spelling repair for words the lists do not hold: a sound key made for Indian
 * spellings, a weighted Damerau-Levenshtein distance, and a letter-pair index to find
 * candidates fast.</li>
 * <li>{@code model}: a softmax (multinomial logistic regression) model over hashed word, letter
 * group and sound features. The application trains it itself at start-up, in the background,
 * from examples generated out of the word lists, then calibrates its confidence.</li>
 * <li>{@code engine}: blends lexicon and model into one confidence, applies the review rules,
 * builds the explanation, and learns from the user.</li>
 * <li>{@code learn}: what each user taught it, in memory for tests and in PostgreSQL for
 * the application.</li>
 * </ol>
 * {@link com.spendanalyzer.ai.SpendCategorizer} is the one Spring bean. Apart from it and the
 * JDBC memory store, nothing here knows about Spring or the database: the engine can be built and
 * tested with {@code new} in milliseconds.
 */
package com.spendanalyzer.ai;
