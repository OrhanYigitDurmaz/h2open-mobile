package tr.h2open.driver.ui.common

/** What a screen shows while its data loads from Odoo. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}
