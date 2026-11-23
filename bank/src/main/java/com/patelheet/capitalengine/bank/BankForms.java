package com.patelheet.capitalengine.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.floodgate.api.FloodgateApi;

/** The only class that touches Floodgate types, so servers without Floodgate never load it. Both calls return false for Java players. */
final class BankForms {
    private BankForms() {}

    static boolean yesNo(UUID player, String title, String content, String yes, String no, Runnable onYes, Runnable onNo) {
        FloodgateApi api = FloodgateApi.getInstance();
        if (!api.isFloodgatePlayer(player)) return false;
        return api.sendForm(player, ModalForm.builder().title(title).content(content).button1(yes).button2(no)
                .validResultHandler(r -> (r.clickedFirst() ? onYes : onNo).run())
                .closedOrInvalidResultHandler(onNo));
    }

    /** One form with a text box per label (numbers, prices, notes), answered together. */
    static boolean inputs(UUID player, String title, List<String> labels, Consumer<List<String>> onDone, Runnable onCancel) {
        FloodgateApi api = FloodgateApi.getInstance();
        if (!api.isFloodgatePlayer(player)) return false;
        CustomForm.Builder form = CustomForm.builder().title(title);
        labels.forEach(l -> form.input(l, ""));
        return api.sendForm(player, form.validResultHandler(r -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < labels.size(); i++) { String v = r.asInput(i); out.add(v == null ? "" : v); }
            onDone.accept(out);
        }).closedOrInvalidResultHandler(onCancel));
    }
}
