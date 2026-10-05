package de.agentcodi.core;

/** Read-only, on-demand report evaluated by the current terminal shell. */
public final class PackageDiagnosticsCommand {
    private PackageDiagnosticsCommand() {
    }

    public static String create() {
        // A subshell keeps report variables out of the interactive session. Only
        // the managed prefix database is queried; PATH fallbacks are shown apart.
        // No package installation, configuration, auth data or network access.
        return "( printf 'Package paths\\nPREFIX=%s\\nHOME=%s\\nTMPDIR=%s\\nPATH=%s\\nLD_LIBRARY_PATH=%s\\nNPM_CONFIG_PREFIX=%s\\nXDG_CACHE_HOME=%s\\n' \"${PREFIX:-<unset>}\" \"${HOME:-<unset>}\" \"${TMPDIR:-<unset>}\" \"${PATH:-<unset>}\" \"${LD_LIBRARY_PATH:-<unset>}\" \"${NPM_CONFIG_PREFIX:-<unset>}\" \"${XDG_CACHE_HOME:-<unset>}\"; "
            + "printf '\\nResolved commands (not proof of an APT installation)\\n'; "
            + "for agentcodi_program in apt dpkg dpkg-query node npm npx python pip git rg; "
            + "do agentcodi_path=$(command -v \"$agentcodi_program\" 2>/dev/null) || agentcodi_path='<unavailable>'; "
            + "printf '%s=%s\\n' \"$agentcodi_program\" \"$agentcodi_path\"; "
            + "done; "
            + "printf '\\nManaged packages: name / version / status\\n'; "
            + "if [ -z \"${PREFIX:-}\" ]; "
            + "then printf 'Package prefix is unavailable.\\n'; "
            + "elif [ ! -r \"$PREFIX/var/lib/dpkg/status\" ]; "
            + "then printf 'Package database is unavailable.\\n'; "
            + "elif [ ! -x \"$PREFIX/bin/dpkg-query\" ]; "
            + "then printf 'Package query tool is unavailable.\\n'; "
            + "else printf 'Database=%s/var/lib/dpkg\\n' \"$PREFIX\"; "
            + "LC_ALL=C \"$PREFIX/bin/dpkg-query\" --admindir=\"$PREFIX/var/lib/dpkg\" -W -f='${Package}\\t${Version}\\t${db:Status-Status}\\n' || { printf 'Package query failed.\\n'; "
            + "exit 1; "
            + "}; "
            + "fi )\n";
    }
}
