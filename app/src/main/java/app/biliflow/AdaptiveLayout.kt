package app.biliflow

fun feedColumns(widthDp: Int, doubleColumn: Boolean): Int =
    if (widthDp >= 600) (widthDp / 280).coerceIn(2, 6) else if (doubleColumn) 2 else 1

fun useSideBySidePlayer(widthDp: Int, heightDp: Int): Boolean = widthDp >= 840 && widthDp > heightDp
