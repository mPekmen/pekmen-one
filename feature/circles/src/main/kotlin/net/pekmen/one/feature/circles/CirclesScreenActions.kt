package net.pekmen.one.feature.circles

import net.pekmen.one.core.model.CirclePeriodType

data class CirclesScreenActions(
    val onSelectCircle: (String) -> Unit = {},
    val onSelectPeriod: (CirclePeriodType) -> Unit = {},
    val onOpenCreateDialog: () -> Unit = {},
    val onDismissCreateDialog: () -> Unit = {},
    val onCreateCircle: (String, String) -> Unit = { _, _ -> },
    val onOpenJoinDialog: () -> Unit = {},
    val onDismissJoinDialog: () -> Unit = {},
    val onJoinCircle: (String, String) -> Unit = { _, _ -> },
    val onOpenLeaveConfirm: () -> Unit = {},
    val onDismissLeaveConfirm: () -> Unit = {},
    val onLeaveCircle: () -> Unit = {},
    val onSetShareEnabled: (Boolean) -> Unit = {},
    val onSetShareLiveStatus: (Boolean) -> Unit = {},
    val onToggleSubject: (String) -> Unit = {},
    val onAddCustomSubject: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissError: () -> Unit = {}
)
