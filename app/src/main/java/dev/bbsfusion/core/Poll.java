package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only poll content returned by the source site; absent results stay absent. */
public final class Poll {
    public final String question;
    public final String status;
    public final List<Option> options;

    public Poll(String question, String status, List<Option> options) {
        this.question = question == null ? "" : question.trim();
        this.status = status == null ? "" : status.trim();
        this.options = options == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(options));
    }

    public static final class Option {
        public final String text;
        public final String result;

        public Option(String text, String result) {
            this.text = text == null ? "" : text.trim();
            this.result = result == null ? "" : result.trim();
        }
    }
}
