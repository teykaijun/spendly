package com.spendly.parser

/**
 * Joins a notification's text fields into one body without repeating content.
 *
 * Android hands a listener the same words several times over: the collapsed
 * text, the expanded "big text" (which usually *contains* the collapsed text),
 * the lines of an inbox-style notification, and the messages of a chat-style
 * one. Concatenating them naively repeats the amount, which the parser then
 * reads as several competing amounts — and used to cost enough confidence to
 * drop ordinary bank alerts outright.
 *
 * Pure Kotlin so it can be tested on the JVM; the listener only reads fields
 * out of the Bundle and passes them here.
 */
object NotificationText {

    fun combine(
        bigText: String? = null,
        text: String? = null,
        subText: String? = null,
        infoText: String? = null,
        /** Inbox-style lines (Gmail, grouped SMS). */
        textLines: List<String> = emptyList(),
        /**
         * The newest message of a chat-style notification. Only the newest:
         * an SMS conversation re-posts every earlier message too, and parsing
         * those again would read yesterday's spend as today's.
         */
        latestMessage: String? = null,
    ): String {
        val parts = buildList {
            latestMessage?.let(::add)
            bigText?.let(::add)
            text?.let(::add)
            addAll(textLines)
            subText?.let(::add)
            infoText?.let(::add)
        }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Decide what to keep longest-first, so a fragment is dropped when a
        // longer part already contains it, whatever order they arrived in.
        val kept = mutableListOf<String>()
        for (part in parts.sortedByDescending { it.length }) {
            if (kept.none { it.contains(part, ignoreCase = true) }) kept += part
        }

        // Emit in the original order so the body still reads naturally.
        return parts.filter { it in kept }.distinct().joinToString(" ")
    }
}
