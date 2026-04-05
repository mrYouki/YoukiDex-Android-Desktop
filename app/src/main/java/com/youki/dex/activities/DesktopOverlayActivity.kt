package com.youki.dex.activities

/**
 * DesktopOverlayActivity — identical to the desktop screen but without the HOME category.
 *
 * Reason: LauncherActivity is registered as a HOME activity, so Samsung kicks it out as soon as
 * it launches from a regular home screen shortcut. This Activity inherits all LauncherActivity code
 * but is declared in the manifest without the HOME intent-filter — the system treats it as a
 * plain Activity and does not interfere with it.
 *
 * Used exclusively for the home screen shortcut.
 */
class DesktopOverlayActivity : LauncherActivity()
