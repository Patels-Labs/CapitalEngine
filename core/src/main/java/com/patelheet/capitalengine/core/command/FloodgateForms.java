package com.patelheet.capitalengine.core.command;

import java.util.UUID;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.floodgate.api.FloodgateApi;

/** The only class that touches Floodgate types, so servers without Floodgate never load it. */
final class FloodgateForms {
    private FloodgateForms() {}

    /** Shows a yes/no form to a Bedrock player. Returns false if the player isn't on Bedrock (use the chat prompt instead). */
    static boolean askYesNo(UUID player, String title, String content, String yes, String no, Runnable onYes, Runnable onNo) {
        FloodgateApi api = FloodgateApi.getInstance();
        if (!api.isFloodgatePlayer(player)) return false;
        return api.sendForm(player, ModalForm.builder().title(title).content(content).button1(yes).button2(no)
                .validResultHandler(r -> (r.clickedFirst() ? onYes : onNo).run())
                .closedOrInvalidResultHandler(onNo));
    }
}
