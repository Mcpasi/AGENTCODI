package de.agentcodi.app;

import android.content.Context;

import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;

final class UiText {
    private static final String RUNTIME_CONNECTION_FAILURE_PREFIX =
        "Codex App-Server-Verbindung fehlgeschlagen: ";

    private UiText() {
    }

    static String phase(Context context, RuntimePhase phase) {
        if (phase == RuntimePhase.STARTING) {
            return context.getString(R.string.runtime_phase_starting);
        }
        if (phase == RuntimePhase.READY) {
            return context.getString(R.string.runtime_phase_ready);
        }
        if (phase == RuntimePhase.FAILED) {
            return context.getString(R.string.runtime_phase_failed);
        }
        if (phase == RuntimePhase.STOPPED) {
            return context.getString(R.string.runtime_phase_stopped);
        }
        return context.getString(R.string.runtime_phase_idle);
    }

    static String runtimeMessage(Context context, RuntimeSnapshot snapshot) {
        if (snapshot.getPhase() == RuntimePhase.IDLE) {
            return context.getString(R.string.runtime_status_idle);
        }
        if (snapshot.getPhase() == RuntimePhase.STARTING) {
            return context.getString(R.string.runtime_status_starting);
        }
        if (snapshot.getPhase() == RuntimePhase.READY) {
            return context.getString(R.string.runtime_status_ready);
        }
        if (snapshot.getPhase() == RuntimePhase.STOPPED) {
            return context.getString(R.string.runtime_status_stopped);
        }
        String raw = snapshot.getMessage();
        if (raw.startsWith(RUNTIME_CONNECTION_FAILURE_PREFIX)) {
            return context.getString(
                R.string.runtime_connection_failed,
                raw.substring(RUNTIME_CONNECTION_FAILURE_PREFIX.length())
            );
        }
        if (raw.isEmpty() || "Unbekannter Runtime-Fehler.".equals(raw)) {
            return context.getString(R.string.runtime_status_unknown_failure);
        }
        int separator = raw.indexOf(": ");
        if (separator > 0) {
            return context.getString(
                R.string.runtime_failure_details,
                raw.substring(0, separator),
                errorReason(context, raw.substring(separator + 2))
            );
        }
        return context.getString(
            R.string.runtime_failure_details,
            raw,
            context.getString(R.string.common_unknown_error)
        );
    }

    static String coreStatus(Context context, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int exact = coreStatusResource(raw);
        if (exact != 0) {
            return context.getString(exact);
        }
        String prefix = "Modell für den nächsten Turn: ";
        if (raw.startsWith(prefix)) {
            return context.getString(R.string.core_model_next, raw.substring(prefix.length()));
        }
        prefix = "Denkstufe für den nächsten Turn: ";
        if (raw.startsWith(prefix)) {
            return context.getString(R.string.core_effort_next, raw.substring(prefix.length()));
        }
        String suffix = " Chat(s) geladen.";
        if (raw.endsWith(suffix)) {
            int count = parseNonNegativeInt(raw.substring(0, raw.length() - suffix.length()));
            if (count >= 0) {
                return context.getResources().getQuantityString(
                    R.plurals.core_chats_loaded,
                    count,
                    Integer.valueOf(count)
                );
            }
        }
        prefix = "Chat ";
        if (raw.startsWith(prefix) && raw.length() > prefix.length()) {
            return context.getString(R.string.core_chat_title, raw.substring(prefix.length()));
        }
        suffix = " hat das private Workspace-Berechtigungsprofil nicht aktiviert.";
        if (raw.endsWith(suffix) && raw.length() > suffix.length()) {
            return context.getString(
                R.string.core_permission_profile_not_activated,
                raw.substring(0, raw.length() - suffix.length())
            );
        }
        suffix = " hat den erforderlichen HTTPS-Modellprovider nicht aktiviert.";
        if (raw.endsWith(suffix) && raw.length() > suffix.length()) {
            return context.getString(
                R.string.core_https_provider_not_activated,
                raw.substring(0, raw.length() - suffix.length())
            );
        }
        return raw;
    }

    static String threadTitle(Context context, String raw) {
        if ("Unbenannter Chat".equals(raw)) {
            return context.getString(R.string.core_unnamed_chat);
        }
        if ("Neuer Chat".equals(raw)) {
            return context.getString(R.string.core_new_chat_title);
        }
        return raw;
    }

    static String errorReason(Context context, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return context.getString(R.string.common_unknown_error);
        }
        String value = raw.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if ("no details".equals(lower)) {
            return context.getString(R.string.common_not_available);
        }
        if (lower.contains("cancel")) {
            return context.getString(R.string.error_reason_cancelled);
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return context.getString(R.string.error_reason_timeout);
        }
        if (lower.contains("changed") || lower.contains("grew")) {
            return context.getString(R.string.error_reason_changed);
        }
        if (lower.contains("symbolic") || lower.contains("symlink")) {
            return context.getString(R.string.error_reason_symbolic_link);
        }
        if (lower.contains("hard-link") || lower.contains("hard link")) {
            return context.getString(R.string.error_reason_hard_link);
        }
        if (lower.contains("escaped") || lower.contains("outside")
            || lower.contains("unsafe") || lower.contains("canonical workspace")) {
            return context.getString(R.string.error_reason_outside_workspace);
        }
        if (lower.contains("cannot verify") || lower.contains("could not verify")) {
            return context.getString(R.string.error_reason_filesystem_verification);
        }
        if (lower.contains("portable") || lower.contains("collide")) {
            return context.getString(R.string.error_reason_archive_name);
        }
        if (lower.contains("limit") || lower.contains("overflow")
            || lower.contains("too many") || lower.contains("exceeds")) {
            return context.getString(R.string.error_reason_limit);
        }
        if (lower.contains("does not exist") || lower.contains("not a regular file")) {
            return context.getString(R.string.error_reason_not_regular);
        }
        if (lower.contains("unsupported")) {
            return context.getString(R.string.error_reason_unsupported);
        }
        if (lower.contains("utf-8") || lower.contains("invalid")
            || lower.contains("malformed")) {
            return context.getString(R.string.error_reason_invalid_data);
        }
        if (lower.contains("transport is closed") || lower.contains("connection closed")
            || lower.contains("closed stdout")) {
            return context.getString(R.string.error_reason_transport_closed);
        }
        if (lower.contains("self-test") || lower.contains("invalid handle")) {
            return context.getString(R.string.error_reason_native_runtime);
        }
        if (lower.contains("document") || lower.contains("content uri")
            || lower.contains("export destination")) {
            return context.getString(R.string.error_reason_destination);
        }
        int exact = coreStatusResource(value);
        return exact == 0 ? value : context.getString(exact);
    }

    static String cardTitle(Context context, CodexTranscriptItem item) {
        String type = item.getProtocolType();
        if (item.getKind() == CodexTranscriptItem.Kind.REASONING
            || "reasoning".equals(type)) {
            return context.getString(R.string.card_reasoning);
        }
        if (item.getKind() == CodexTranscriptItem.Kind.PLAN || "plan".equals(type)) {
            return context.getString(R.string.card_plan);
        }
        if ("commandExecution".equals(type)) {
            return context.getString(R.string.card_command);
        }
        if ("fileChange".equals(type)) {
            return context.getString(R.string.card_file_change);
        }
        if ("mcpToolCall".equals(type)) {
            return context.getString(R.string.card_mcp_tool);
        }
        if ("dynamicToolCall".equals(type)) {
            return context.getString(R.string.card_dynamic_tool);
        }
        if ("collabAgentToolCall".equals(type)) {
            return context.getString(R.string.card_agent_tool);
        }
        if ("subAgentActivity".equals(type)) {
            return context.getString(R.string.card_subagent);
        }
        if ("webSearch".equals(type)) {
            return context.getString(R.string.card_web_search);
        }
        if ("imageView".equals(type)) {
            return context.getString(R.string.card_image_view);
        }
        if ("sleep".equals(type)) {
            return context.getString(R.string.card_wait);
        }
        if ("imageGeneration".equals(type)) {
            return context.getString(R.string.card_image_generation);
        }
        if ("hookPrompt".equals(type)) {
            return context.getString(R.string.card_hook);
        }
        if ("enteredReviewMode".equals(type) || "exitedReviewMode".equals(type)) {
            return context.getString(R.string.card_review_mode);
        }
        if ("contextCompaction".equals(type)) {
            return context.getString(R.string.card_context_compaction);
        }
        return context.getString(R.string.card_tool_activity);
    }

    static String cardSummary(Context context, CodexTranscriptItem item) {
        String raw = item.getSummary();
        String type = item.getProtocolType();
        if ("fileChange".equals(type)) {
            if ("Änderungsdetails werden vorbereitet.".equals(raw)) {
                return context.getString(R.string.card_change_preparing);
            }
            if ("Keine darstellbaren Änderungsdetails.".equals(raw)) {
                return context.getString(R.string.card_no_change_details);
            }
            int space = raw.indexOf(' ');
            int count = space <= 0 ? -1 : parseNonNegativeInt(raw.substring(0, space));
            if (count >= 0 && (raw.endsWith(" Dateiänderung")
                || raw.endsWith(" Dateiänderungen"))) {
                return context.getResources().getQuantityString(
                    R.plurals.card_file_change_count,
                    count,
                    Integer.valueOf(count)
                );
            }
        }
        if (("enteredReviewMode".equals(type) || "exitedReviewMode".equals(type))) {
            return "Gestartet".equals(raw)
                ? context.getString(R.string.card_started)
                : "Beendet".equals(raw) ? context.getString(R.string.card_ended) : raw;
        }
        if ("contextCompaction".equals(type)
            && "Kontext wurde für den weiteren Turn verdichtet.".equals(raw)) {
            return context.getString(R.string.card_context_compacted);
        }
        return raw;
    }

    static String cardDetail(Context context, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] sections = raw.split("\\n\\n", -1);
        StringBuilder localized = new StringBuilder(raw.length());
        for (int index = 0; index < sections.length; index++) {
            if (index != 0) {
                localized.append("\n\n");
            }
            localized.append(localizeCardSection(context, sections[index]));
        }
        return localized.toString();
    }

    private static String localizeCardSection(Context context, String section) {
        int newline = section.indexOf('\n');
        if (newline < 0) {
            return localizedCardValue(context, section);
        }
        String heading = section.substring(0, newline);
        String value = section.substring(newline + 1);
        String localizedHeading = localizeCardHeading(context, heading);
        return localizedHeading + "\n" + localizedCardValue(context, value);
    }

    private static String localizeCardHeading(Context context, String heading) {
        if (heading.startsWith("HINZUFÜGEN · ")) {
            return context.getString(R.string.card_change_add)
                + heading.substring("HINZUFÜGEN".length());
        }
        if (heading.startsWith("LÖSCHEN · ")) {
            return context.getString(R.string.card_change_delete)
                + heading.substring("LÖSCHEN".length());
        }
        if (heading.startsWith("ÄNDERN · ")) {
            return context.getString(R.string.card_change_update)
                + heading.substring("ÄNDERN".length());
        }
        if (!heading.endsWith(":")) {
            return heading;
        }
        String label = heading.substring(0, heading.length() - 1);
        int resource = cardFieldResource(label);
        return resource == 0 ? heading : context.getString(resource) + ":";
    }

    private static String localizedCardValue(Context context, String value) {
        if ("Die App prüft den gemeldeten Pfad gegen den tatsächlichen privaten Workspace."
            .equals(value)) {
            return context.getString(R.string.card_image_path_check);
        }
        if ("Bild erzeugt; eingebettete Bilddaten wurden nicht in den UI-Zustand übernommen."
            .equals(value)) {
            return context.getString(R.string.card_image_result_compacted);
        }
        if ("Die App prüft den gemeldeten Pfad kanonisch. Erst nach erfolgreicher Prüfung wird der Export freigeschaltet."
            .equals(value)) {
            return context.getString(R.string.card_image_export_check);
        }
        if ("Nicht angeboten: Der gemeldete Pfad ist kein sicher prüfbarer absoluter Dateipfad."
            .equals(value)) {
            return context.getString(R.string.card_image_export_unavailable);
        }
        if ("Kein Text-Diff vorhanden.".equals(value)) {
            return context.getString(R.string.card_no_text_diff);
        }
        if ("Inhalt konnte nicht sicher dargestellt werden.".equals(value)) {
            return context.getString(R.string.card_content_unavailable);
        }
        if ("Ja".equals(value)) {
            return context.getString(R.string.card_yes);
        }
        if ("Nein".equals(value)) {
            return context.getString(R.string.card_no);
        }
        if ("… Ausgabe gekürzt …".equals(value)) {
            return context.getString(R.string.core_output_truncated);
        }
        return value;
    }

    private static int cardFieldResource(String label) {
        if ("Thread".equals(label)) return R.string.card_field_thread;
        if ("Aktivität".equals(label)) return R.string.card_field_activity;
        if ("Aktion".equals(label)) return R.string.card_field_action;
        if ("Ergebnisse".equals(label)) return R.string.card_field_results;
        if ("Überarbeiteter Prompt".equals(label)) return R.string.card_field_revised_prompt;
        if ("Gemeldeter Speicherpfad".equals(label)) return R.string.card_field_reported_path;
        if ("Ergebnis".equals(label)) return R.string.card_field_result;
        if ("Export".equals(label)) return R.string.card_field_export;
        if ("Arbeitsverzeichnis".equals(label)) return R.string.card_field_working_directory;
        if ("Quelle".equals(label)) return R.string.card_field_source;
        if ("Aktionen".equals(label)) return R.string.card_field_actions;
        if ("Plugin".equals(label)) return R.string.card_field_plugin;
        if ("Skript".equals(label)) return R.string.card_field_script;
        if ("Exit-Code".equals(label)) return R.string.card_field_exit_code;
        if ("Dauer".equals(label)) return R.string.card_field_duration;
        if ("Ausgabe".equals(label)) return R.string.card_field_output;
        if ("Argumente".equals(label)) return R.string.card_field_arguments;
        if ("App-Kontext".equals(label)) return R.string.card_field_app_context;
        if ("Fortschritt".equals(label)) return R.string.card_field_progress;
        if ("Fehler".equals(label)) return R.string.card_field_error;
        if ("Erfolg".equals(label)) return R.string.card_field_success;
        if ("Prompt".equals(label)) return R.string.card_field_prompt;
        if ("Modell".equals(label)) return R.string.card_field_model;
        if ("Denkstufe".equals(label)) return R.string.card_field_effort;
        if ("Sender".equals(label)) return R.string.card_field_sender;
        if ("Empfänger".equals(label)) return R.string.card_field_receiver;
        if ("Agentenstatus".equals(label)) return R.string.card_field_agent_status;
        if ("Hook".equals(label)) return R.string.card_hook;
        return 0;
    }

    private static int coreStatusResource(String raw) {
        if ("Codex App-Server startet.".equals(raw)) return R.string.core_server_starting;
        if ("Codex App-Server wird initialisiert.".equals(raw)) return R.string.core_server_initializing;
        if ("Codex App-Server ist bereit.".equals(raw)) return R.string.core_server_ready;
        if ("Codex App-Server konnte nicht initialisiert werden.".equals(raw)) return R.string.core_server_initialize_failed;
        if ("Konto und Chats werden aktualisiert.".equals(raw)) return R.string.core_refreshing_account_chats;
        if ("Codex App-Server ist nicht bereit.".equals(raw)) return R.string.core_server_not_ready;
        if ("Das gewählte Modell wird vom App-Server nicht angeboten.".equals(raw)) return R.string.core_model_not_offered;
        if ("Das Modell kann erst nach dem laufenden Turn gewechselt werden.".equals(raw)) return R.string.core_model_change_during_turn;
        if ("Diese Denkstufe wird vom gewählten Modell nicht unterstützt.".equals(raw)) return R.string.core_effort_unsupported;
        if ("Die Denkstufe kann erst nach dem laufenden Turn gewechselt werden.".equals(raw)) return R.string.core_effort_change_during_turn;
        if ("ChatGPT-Anmeldung wird vorbereitet.".equals(raw)) return R.string.core_chatgpt_login_preparing;
        if ("Anmeldeseite im Browser öffnen.".equals(raw)) return R.string.core_open_login_browser;
        if ("Der API-Schlüssel hat eine ungültige Länge.".equals(raw)) return R.string.core_api_key_invalid_length;
        if ("API-Schlüssel wird an Codex übergeben.".equals(raw)) return R.string.core_api_key_submitting;
        if ("API-Schlüssel wurde im kanonischen Codex-Speicher abgelegt.".equals(raw)) return R.string.core_api_key_stored;
        if ("Abmeldung läuft.".equals(raw)) return R.string.core_sign_out_running;
        if ("Abgemeldet.".equals(raw)) return R.string.core_signed_out;
        if ("Chats werden geladen.".equals(raw)) return R.string.core_chats_loading;
        if ("Neuer Chat wird erstellt.".equals(raw)) return R.string.core_new_chat_creating;
        if ("Ungültige Chat-ID.".equals(raw)) return R.string.core_invalid_chat_id;
        if ("Chat wird geöffnet.".equals(raw)) return R.string.core_chat_opening;
        if ("Chat ist geöffnet.".equals(raw)) return R.string.core_chat_open;
        if ("Nachrichten müssen 1 bis 32768 Zeichen enthalten.".equals(raw)) return R.string.core_message_length;
        if ("Nachricht wird gesendet.".equals(raw)) return R.string.core_message_sending;
        if ("Bitte zuerst anmelden.".equals(raw)) return R.string.core_sign_in_first;
        if ("Der aktuelle Turn läuft noch.".equals(raw)) return R.string.core_turn_still_running;
        if ("Kein stoppbarer Turn ist aktiv.".equals(raw)) return R.string.core_no_stoppable_turn;
        if ("Bitte ein angebotenes Modell und eine Denkstufe wählen.".equals(raw)) return R.string.core_choose_model_effort;
        if ("Codex arbeitet.".equals(raw)) return R.string.core_codex_working;
        if ("Turn wird gestoppt.".equals(raw)) return R.string.core_turn_stopping;
        if ("Freigabeentscheidung wird übermittelt.".equals(raw)) return R.string.core_approval_sending;
        if ("Eine Freigabeentscheidung fehlt.".equals(raw)) return R.string.core_approval_missing;
        if ("Diese Freigabe ist nicht mehr aktiv.".equals(raw)) return R.string.core_approval_expired;
        if ("Diese Anfrage erwartet eine Texteingabe.".equals(raw)) return R.string.core_request_expects_text;
        if ("Diese Eingabeanfrage ist nicht mehr aktiv.".equals(raw)) return R.string.core_input_expired;
        if ("Diese Anfrage erwartet eine Freigabeentscheidung.".equals(raw)) return R.string.core_request_expects_approval;
        if ("Antwort wird an Codex übermittelt.".equals(raw)) return R.string.core_answer_sending;
        if ("Eingabeanfrage wird ohne Antwort geschlossen.".equals(raw)) return R.string.core_input_closing_empty;
        if ("Codex wartet auf deine Eingabe.".equals(raw)) return R.string.core_waiting_for_input;
        if ("Codex wartet auf deine Freigabe.".equals(raw)) return R.string.core_waiting_for_approval;
        if ("Verbindung zum Codex App-Server wurde beendet.".equals(raw)) return R.string.core_connection_ended;
        if ("Codex App-Server wurde gestoppt.".equals(raw)) return R.string.core_server_stopped;
        if ("Das private AGENTCODI-Workspace-Berechtigungsprofil ist nicht verfügbar.".equals(raw)) return R.string.core_permission_profile_missing;
        if ("Der Codex App-Server bietet kein auswählbares Modell an.".equals(raw)) return R.string.core_no_selectable_model;
        if ("Noch keine Chats vorhanden.".equals(raw)) return R.string.core_no_chats;
        if ("Bitte zuerst ein angebotenes Modell wählen.".equals(raw)) return R.string.core_choose_model_first;
        if ("Neuer Chat ist bereit.".equals(raw)) return R.string.core_new_chat_ready;
        if ("Turn wurde gestoppt.".equals(raw)) return R.string.core_turn_stopped;
        if ("Turn ist fehlgeschlagen.".equals(raw)) return R.string.core_turn_failed;
        if ("Antwort abgeschlossen.".equals(raw)) return R.string.core_answer_complete;
        if ("Codex hat einen nicht näher bezeichneten Fehler gemeldet.".equals(raw)) return R.string.core_unspecified_error;
        if ("Anmeldung wurde nicht abgeschlossen.".equals(raw)) return R.string.core_login_incomplete;
        if ("Eine andere Codex-Aktion läuft bereits.".equals(raw)) return R.string.core_other_action_running;
        if ("Codex Runtime wird beendet.".equals(raw)) return R.string.core_runtime_shutting_down;
        if ("Eine Codex-Anfrage ist sicher abgelaufen.".equals(raw)) return R.string.core_request_timed_out;
        if ("Codex hat die Anfrage geschlossen.".equals(raw)) return R.string.core_request_closed;
        if ("Eine Antwort an den Codex App-Server ist fehlgeschlagen.".equals(raw)) return R.string.core_response_failed;
        if ("Unbekannter Codex-Fehler".equals(raw)) return R.string.core_unknown_codex_error;
        if ("Unbekannte Freigabeentscheidung.".equals(raw)) return R.string.core_unknown_approval;
        if ("Bitte alle Fragen beantworten.".equals(raw)) return R.string.core_answer_all_questions;
        if ("Bitte alle Fragen gültig beantworten.".equals(raw)) return R.string.core_answer_all_questions_valid;
        if ("Für diese Freigabe wurde keine Befehlsregel vorgeschlagen.".equals(raw)) return R.string.core_command_rule_missing;
        if ("Die vorgeschlagene Netzwerkregel ist nicht mehr verfügbar.".equals(raw)) return R.string.core_network_rule_missing;
        if ("Diese Entscheidung ist nur für Befehlsfreigaben zulässig.".equals(raw)) return R.string.core_command_approval_only;
        if ("Die Dateiänderungsdetails sind noch nicht verfügbar; bitte nicht blind freigeben.".equals(raw)) return R.string.core_file_details_missing;
        if ("Die Befehlsdetails sind noch nicht verfügbar; bitte nicht blind freigeben.".equals(raw)) return R.string.core_command_details_missing;
        if ("Eine Freigabe außerhalb des privaten Workspace ist nicht zulässig.".equals(raw)) return R.string.core_approval_outside_workspace;
        if ("Eine Dateiänderung außerhalb des privaten Workspace ist nicht zulässig.".equals(raw)) return R.string.core_file_outside_workspace;
        if ("Der laufende Turn muss zuerst abgeschlossen oder gestoppt werden.".equals(raw)) return R.string.core_finish_turn_first;
        if ("Codex App-Server ist nicht gestartet.".equals(raw)) return R.string.core_server_not_started;
        return 0;
    }

    private static int parseNonNegativeInt(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? -1 : parsed;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
