package chat.ratatosk.android.ui.model

import android.app.Application
import chat.ratatosk.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiArchivePeek
import org.ratatosk.core.FfiArchiveUnlock
import org.ratatosk.core.FfiExportScope
import org.ratatosk.core.FfiExported
import org.ratatosk.core.FfiImported

/** Что окно умеет делать с резервной копией. */
interface BackupApi {
    /** Ответ приходит и при неудаче: иначе диалог остаётся «в работе» навсегда. */
    fun exportHistory(scope: FfiExportScope, phrase: String?, onResult: (Result<FfiExported>) -> Unit)
    fun importArchive(path: String, unlock: FfiArchiveUnlock, label: String, onResult: (Result<FfiImported>) -> Unit)
    fun peekArchive(path: String, onResult: (FfiArchivePeek?) -> Unit)
}

/**
 * Выгрузка и ввоз архива переписки.
 *
 * @param onImported перечитать список аккаунтов: ввезённый архив — это новый аккаунт.
 */
class BackupModel(
    private val session: SessionContext,
    private val onImported: () -> Unit,
) : BackupApi {

    override fun exportHistory(scope: FfiExportScope, phrase: String?, onResult: (Result<FfiExported>) -> Unit) {
        session.scope.launch(Dispatchers.IO) {
            try {
                // Каталог приложения на общем хранилище, а не публичные
                // «Загрузки». Архив пишет **ядро**, ему нужен настоящий путь
                // в файловой системе, поэтому MediaStore здесь не подходит,
                // а прямая запись в публичные «Загрузки» с Android 10
                // запрещена без разрешений, которых у приложения нет: она
                // отваливалась EACCES, а человек видел только «Export failed».
                //
                // Этот путь разрешений не требует и виден проводником:
                // Android/data/chat.ratatosk.android/files/Download/ratatosk_backups.
                val downloadsDir = session.app
                    .getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: session.app.filesDir
                val ratatoskDir = java.io.File(downloadsDir, "ratatosk_backups")
                ratatoskDir.mkdirs()
                val fileName = "ratatosk_backup_${System.currentTimeMillis()}.db"
                val dest = java.io.File(ratatoskDir, fileName)

                val result = session.core.exportHistory(dest.absolutePath, scope, phrase)
                withContext(Dispatchers.Main) {
                    onResult(Result.success(result))
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Export failed", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.export_failed)
                    onResult(Result.failure(e))
                }
            }
        }
    }

    override fun importArchive(path: String, unlock: FfiArchiveUnlock, label: String, onResult: (Result<FfiImported>) -> Unit) {
        session.scope.launch(Dispatchers.IO) {
            try {
                val result = session.core.importArchive(session.app, path, unlock, label)
                withContext(Dispatchers.Main) {
                    onImported()
                    onResult(Result.success(result))
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Import failed", e)
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }

    override fun peekArchive(path: String, onResult: (FfiArchivePeek?) -> Unit) {
        session.scope.launch(Dispatchers.IO) {
            try {
                val result = session.core.peekArchive(path)
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Peek failed", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_archive_read_failed)
                    onResult(null)
                }
            }
        }
    }
}
