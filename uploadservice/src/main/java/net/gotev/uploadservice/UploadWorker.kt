package net.gotev.uploadservice

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import net.gotev.uploadservice.data.UploadNotificationConfig
import net.gotev.uploadservice.data.UploadTaskParameters
import net.gotev.uploadservice.extensions.UploadTaskCreationParameters
import net.gotev.uploadservice.extensions.getUploadTask
import net.gotev.uploadservice.logger.UploadServiceLogger
import net.gotev.uploadservice.observer.task.BroadcastEmitter
import net.gotev.uploadservice.observer.task.TaskCompletionNotifier
import net.gotev.uploadservice.persistence.PersistableData
import java.util.concurrent.ConcurrentHashMap

class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        internal val TAG = UploadWorker::class.java.simpleName

        private const val UPLOAD_NOTIFICATION_BASE_ID = 1234 // Something unique

        const val TASK_CREATION_PARAMS_KEY = "task-creation-params-key"

        private var notificationIncrementalId = 0
        private val uploadTasksMap = ConcurrentHashMap<String, UploadTask>()

        @Volatile
        private var foregroundUploadId: String? = null

        /**
         * Stops the upload task with the given uploadId.
         * @param uploadId The unique upload id
         */
        @Synchronized
        @JvmStatic
        fun stopUpload(uploadId: String) {
            uploadTasksMap[uploadId]?.cancel()
        }

        /**
         * Gets the list of the currently active upload tasks.
         * @return list of uploadIDs or an empty list if no tasks are currently running
         */
        @JvmStatic
        val taskList: List<String>
            @Synchronized get() = if (uploadTasksMap.isEmpty()) {
                emptyList()
            } else {
                uploadTasksMap.keys().toList()
            }

        /**
         * Stop all the active uploads.
         */
        @Synchronized
        @JvmStatic
        fun stopAllUploads() {
            val iterator = uploadTasksMap.keys.iterator()

            while (iterator.hasNext()) {
                uploadTasksMap[iterator.next()]?.cancel()
            }
        }

        /**
         * Stops the service.
         * @param context application context
         * @param forceStop if true stops the service no matter if some tasks are running, else
         * stops only if there aren't any active tasks
         * @return true if the service is getting stopped, false otherwise
         */
        @Synchronized
        @JvmOverloads
        @JvmStatic
        fun stop(context: Context, forceStop: Boolean = false) =  stopAllUploads()
    }

    var notificationConfig: (context: Context, uploadId: String) -> UploadNotificationConfig =
        UploadServiceConfig.notificationConfigFactory

    private val taskObservers by lazy {
        arrayOf(
            BroadcastEmitter(context),
            UploadServiceConfig.notificationHandlerFactoryWorker(this),
            TaskCompletionNotifier(worker = this)
        )
    }

    private val notificationActionsObserver by lazy {
        UploadServiceConfig.notificationActionsObserverFactory(context)
    }

    @Synchronized
    fun holdForegroundNotification(uploadId: String, notification: Notification): Boolean {
        if (foregroundUploadId == null) {
            foregroundUploadId = uploadId
        }

        if (uploadId == foregroundUploadId) {
            showNotification(UPLOAD_NOTIFICATION_BASE_ID, notification)
            return true
        }

        return false
    }

    @Synchronized
    fun taskCompleted(uploadId: String) {
        removeNotification(UPLOAD_NOTIFICATION_BASE_ID)
        val task = uploadTasksMap.remove(uploadId)

        // un-hold foreground upload ID if it's been hold
        if (UploadServiceConfig.isForegroundService && task != null && task.params.id == foregroundUploadId) {
            UploadServiceLogger.debug(TAG, uploadId) { "now un-holded foreground notification" }
            foregroundUploadId = null
        }

        if (UploadServiceConfig.isForegroundService && uploadTasksMap.isEmpty()) {
            UploadServiceLogger.debug(TAG, UploadServiceLogger.NA) { "All tasks completed, stopping foreground execution" }
        }
    }

    override fun doWork(): Result {
        setup()
        val success = performWork()
        return if (success) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private fun setup() {
        notificationActionsObserver.register()
    }

    private fun performWork(): Boolean {
        UploadServiceLogger.debug(TAG, UploadServiceLogger.NA) {
            "Starting UploadWorker. Debug info: $UploadServiceConfig"
        }

        val builder = NotificationCompat.Builder(applicationContext, UploadServiceConfig.defaultNotificationChannel!!)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setGroup(UploadServiceConfig.namespace)

        val notification = builder.build()
        showNotification(UPLOAD_NOTIFICATION_BASE_ID, notification)

        val taskCreationParameters = getUploadTaskCreationParameters() ?: return false

        if (uploadTasksMap.containsKey(taskCreationParameters.params.id)) {
            UploadServiceLogger.error(TAG, taskCreationParameters.params.id) {
                "Preventing upload! An upload with the same ID is already in progress. " +
                        "Every upload must have unique ID. Please check your code and fix it!"
            }
            return false
        }

        // increment by 2 because the notificationIncrementalId + 1 is used internally
        // in each UploadTask. Check its sources for more info about this.
        notificationIncrementalId += 2

        val currentTask = applicationContext.getUploadTask(
            creationParameters = taskCreationParameters,
            notificationId = UPLOAD_NOTIFICATION_BASE_ID + notificationIncrementalId,
            observers = taskObservers
        ) ?: return false

        uploadTasksMap[currentTask.params.id] = currentTask
        UploadServiceConfig.threadPool.execute(currentTask)

        return true
    }

    private fun getUploadTaskCreationParameters(): UploadTaskCreationParameters? {
        val taskCreationParamsString = inputData.getString(TASK_CREATION_PARAMS_KEY)
        var taskParams: UploadTaskParameters? = null
        taskCreationParamsString?.let {
            taskParams = UploadTaskParameters.createFromPersistableData(PersistableData.fromJson(it))
        }
        taskParams?.let {params ->
            return UploadTaskCreationParameters(
                params = params,
                notificationConfig = notificationConfig(applicationContext, params.id)
            )
        }
        return null
    }

    private fun showNotification(notificationId: Int,notification: Notification) {
        val notificationManager = NotificationManagerCompat.from(applicationContext)

        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(notificationId, notification)
        }
    }

    private fun removeNotification(notificationId: Int) {
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        notificationManager.cancel(notificationId)
    }

}