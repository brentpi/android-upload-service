package net.gotev.uploadservice.extensions

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build.VERSION.SDK_INT
import android.os.Parcelable
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import net.gotev.uploadservice.UploadServiceConfig
import net.gotev.uploadservice.UploadTask
import net.gotev.uploadservice.UploadWorker
import net.gotev.uploadservice.data.UploadNotificationConfig
import net.gotev.uploadservice.data.UploadTaskParameters
import net.gotev.uploadservice.logger.UploadServiceLogger
import net.gotev.uploadservice.logger.UploadServiceLogger.NA
import net.gotev.uploadservice.observer.task.UploadTaskObserver

// constants used in the intent which starts this service
private const val taskParametersKey = "taskParameters"
private const val taskNotificationConfig = "taskUploadConfig"

private const val actionKey = "action"
private const val uploadIdKey = "uploadId"
private const val cancelUploadAction = "cancelUpload"

fun Context.startNewUpload(
    params: UploadTaskParameters,
    notificationConfig: UploadNotificationConfig
): String {
    val inputData = Data.Builder()
        .putString(UploadWorker.TASK_CREATION_PARAMS_KEY, params.toPersistableData().toJson())
        .build()
    val uploadWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(inputData).build()
    WorkManager.getInstance(this).enqueue(uploadWorkRequest)

    return params.id
}

data class UploadTaskCreationParameters(
    val params: UploadTaskParameters,
    val notificationConfig: UploadNotificationConfig
)

fun Intent?.getUploadTaskCreationParameters(): UploadTaskCreationParameters? {
    if (this == null || action != UploadServiceConfig.uploadAction) {
        UploadServiceLogger.error(
            component = UploadWorker.TAG,
            uploadId = NA,
            message = {
                "Error while instantiating new task. Invalid intent received"
            }
        )
        return null
    }

    val params: UploadTaskParameters = parcelableCompat(taskParametersKey) ?: run {
        UploadServiceLogger.error(
            component = UploadWorker.TAG,
            uploadId = NA,
            message = {
                "Error while instantiating new task. Missing task parameters."
            }
        )
        return null
    }

    val taskClass = try {
        Class.forName(params.taskClass)
    } catch (exc: Throwable) {
        UploadServiceLogger.error(
            component = UploadWorker.TAG,
            uploadId = NA,
            exception = exc,
            message = {
                "Error while instantiating new task. ${params.taskClass} does not exist."
            }
        )
        null
    } ?: return null

    if (!UploadTask::class.java.isAssignableFrom(taskClass)) {
        UploadServiceLogger.error(
            component = UploadWorker.TAG,
            uploadId = NA,
            message = {
                "Error while instantiating new task. ${params.taskClass} does not extend UploadTask."
            }
        )
        return null
    }

    val notificationConfig: UploadNotificationConfig =
        parcelableCompat(taskNotificationConfig) ?: run {
            UploadServiceLogger.error(
                component = UploadWorker.TAG,
                uploadId = NA,
                message = {
                    "Error while instantiating new task. Missing notification config."
                }
            )
            return null
        }

    return UploadTaskCreationParameters(
        params = params,
        notificationConfig = notificationConfig
    )
}

/**
 * Creates a new task instance based on the requested task class in the intent.
 * @return task instance or null if the task class is not supported or invalid
 */
@Suppress("UNCHECKED_CAST")
fun Context.getUploadTask(
    creationParameters: UploadTaskCreationParameters,
    notificationId: Int,
    vararg observers: UploadTaskObserver
): UploadTask? {
    return try {
        val taskClass = Class.forName(creationParameters.params.taskClass) as Class<out UploadTask>
        val uploadTask = taskClass.newInstance().apply {
            init(
                context = this@getUploadTask,
                taskParams = creationParameters.params,
                notificationConfig = creationParameters.notificationConfig,
                notificationId = notificationId,
                taskObservers = observers
            )
        }

        UploadServiceLogger.debug(
            component = UploadWorker.TAG,
            uploadId = NA,
            message = {
                "Successfully created new task with class: ${taskClass.name}"
            }
        )
        uploadTask
    } catch (exc: Throwable) {
        UploadServiceLogger.error(
            component = UploadWorker.TAG,
            uploadId = NA,
            exception = exc,
            message = {
                "Error while instantiating new task"
            }
        )
        null
    }
}

fun Context.getNotificationActionIntent(
    uploadId: String,
    action: String
): PendingIntent {
    val intent = Intent(UploadServiceConfig.broadcastNotificationAction).apply {
        `package` = UploadServiceConfig.namespace
        putExtra(actionKey, action)
        putExtra(uploadIdKey, uploadId)
    }

    return PendingIntent.getBroadcast(
        this,
        // this is to prevent duplicate PendingIntent request codes which can cause cancelling
        // the wrong upload
        uploadId.hashCode(),
        intent,
        flagsCompat(PendingIntent.FLAG_ONE_SHOT)
    )
}

fun Context.getCancelUploadIntent(uploadId: String) =
    getNotificationActionIntent(uploadId, cancelUploadAction)

val Intent.uploadIdToCancel: String?
    get() {
        if (getStringExtra(actionKey) != cancelUploadAction) return null
        return getStringExtra(uploadIdKey)
    }

// Adjusts flags for Android 12+
fun flagsCompat(flags: Int): Int {
    if (SDK_INT > 30) {
        return flags or PendingIntent.FLAG_IMMUTABLE
    }

    return flags
}

inline fun <reified T : Parcelable> Intent.parcelableCompat(key: String): T? = when {
    SDK_INT >= 34 -> getParcelableExtra(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
}

@SuppressLint("UnspecifiedRegisterReceiverFlag")
fun Context.registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (SDK_INT >= 34) {
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
}
