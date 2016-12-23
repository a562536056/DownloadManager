package org.aiwen.downloader;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import org.aiwen.downloader.utils.Constants;
import org.aiwen.downloader.utils.Utils;

import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 维护下载的服务
 *
 * Created by wangdan on 16/11/18.
 */
public class DownloadService extends Service {

    private static final String TAG = Constants.TAG + "_Service";

    public static final String ACTION_REQUEST = "org.aisen.downloader.ACTION_REQUEST";

    public static void request(Context context, String key) {
        Intent service = new Intent(context, DownloadService.class);
        service.putExtra("key", key);
        service.setAction(ACTION_REQUEST);

        context.startService(service);
    }

    final int NOTIFY_INTERVAL = 700;// 间隔刷新Download_Observer的时间
    final int MSG_UPDATE = 1000;
    final int MSG_NOTIFY = 1001;

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private AlarmManager mAlarmManager;
    private ThreadPoolExecutor mExecutor;
    private AtomicBoolean mResourceDestoryed = new AtomicBoolean(false);
    private AtomicInteger mThreadCount = new AtomicInteger();

    @Override
    public void onCreate() {
        super.onCreate();

        DLogger.w(TAG, "Service onCreate()");

        Configuration config = Hawk.getInstance().getConfiguration();

        int maxDownloadAllowed = config.getMaxConcurrentDownloadsAllowed();
        mExecutor = new ThreadPoolExecutor(maxDownloadAllowed,
                                           maxDownloadAllowed,
                                           10L,
                                           TimeUnit.SECONDS,
                                           new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {

                                                private final AtomicInteger mCount = new AtomicInteger(1);

                                                @Override
                                                public Thread newThread(Runnable runnable) {
                                                    return new Thread(runnable, "DownloadThread #" + mCount.getAndIncrement());
                                                }

                                            });
        mExecutor.allowCoreThreadTimeOut(true);
        mHandlerThread = new HandlerThread("DownloadManager HandlerThread", Thread.MIN_PRIORITY);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), mCallback);
        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        enqueueNotify(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        do {
            if (intent == null || TextUtils.isEmpty(intent.getAction())) {
                break;
            }

            if (ACTION_REQUEST.equals(intent.getAction())) {
                enqueueUpdate(intent.getStringExtra("key"), 0);
            }

        } while (false);

        return super.onStartCommand(intent, flags, startId);
    }

    // 取消重连闹钟
    void cancelRetryAlarm(Request request) {
        DLogger.i(TAG, "取消重连闹钟, key = %s", request.key);

        final Intent intent = new Intent(Constants.ACTION_RETRY);
        intent.putExtra("key", request.key);
        intent.setClass(this, DownloadReceiver.class);
        mAlarmManager.cancel(PendingIntent.getBroadcast(this, (int) request.id, intent, PendingIntent.FLAG_ONE_SHOT));
    }

    // 设置这个Request的下一次下载重连闹钟
    void setRetryAlarm(Request request) {
        long nextActionMillis = request.downloadInfo.retryAfter;

        DLogger.i(TAG, "scheduling start in %d ms, key = %s", nextActionMillis, request.key);

        final Intent intent = new Intent(Constants.ACTION_RETRY);
        intent.putExtra("key", request.key);
        intent.setClass(this, DownloadReceiver.class);
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, Utils.now() + nextActionMillis,
                PendingIntent.getBroadcast(this, (int) request.id, intent, PendingIntent.FLAG_ONE_SHOT));
    }

    void enqueueUpdate(String key, long delay) {
        if (isResourceDestoryed()) {
            return;
        }

        if (mHandler != null) {
            if (delay > 0) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_UPDATE, key), delay);
            }
            else {
                mHandler.obtainMessage(MSG_UPDATE, key).sendToTarget();
            }
        }
    }

    public void enqueueNotify(boolean delay) {
        if (isResourceDestoryed()) {
            return;
        }

        if (mHandler != null) {
            mHandler.removeMessages(MSG_NOTIFY);
            if (delay) {
                mHandler.sendEmptyMessageDelayed(MSG_NOTIFY, NOTIFY_INTERVAL);
            }
            else {
                mHandler.obtainMessage(MSG_NOTIFY).sendToTarget();
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    Handler.Callback mCallback = new Handler.Callback() {

        @Override
        public boolean handleMessage(Message message) {
            int what = message.what;

            switch (what) {
                case MSG_NOTIFY:
                    handleNotify();
                    break;
                case MSG_UPDATE:
                    handleUpdate(message.obj.toString());
                    break;
            }

            return true;
        }

    };

    private void handleNotify() {
        // 处理是否还有下载
        Hawk hawk = Hawk.getInstance();
        if (hawk == null) {
            return;
        }

        Set<String> keys = hawk.mRequestMap.keySet();

        float speed = 0.0f;
        float averageSpeed = 0.0f;
        for (String key : keys) {
            Request request = hawk.mRequestMap.get(key);

            boolean notifyEvent = false;

            // 正在下载
            if (Downloads.Status.isStatusRunning(request.downloadInfo.status)) {
                notifyEvent = true;

                if (request.trace != null) {
                    synchronized (request.trace) {
                        request.trace.endSpeedCount();
                        DLogger.v(Utils.getDownloaderTAG(request), "下载速度(%d), 平均速度(%d)", (int) request.trace.getSpeed(), (int) request.trace.getAverageSpeed());
                        speed += request.trace.getSpeed();
                        averageSpeed += request.trace.getAverageSpeed();
                        request.trace.beginSpeedCount();
                    }
                }
            }
            else if (Downloads.Status.isStatusError(request.downloadInfo.status)) {
                notifyEvent = true;

                DLogger.e(Utils.getDownloaderTAG(request), "下载失败(%d, %s)", request.downloadInfo.status, request.downloadInfo.error);
            }
            else if (Downloads.Status.isStatusSuccess(request.downloadInfo.status)) {
                if (request.trace != null) {
                    DLogger.v(Utils.getDownloaderTAG(request), "下载结束，下载耗时 %d ms， 总耗时 %d ms，平均速度 %d kb/s", request.trace.getTime(), request.trace.getRealTime(), (int) request.trace.getAverageSpeed());
                }
            }

            if (notifyEvent) {
                // TODO
            }
        }
        hawk.trace.speed = speed;
        hawk.trace.averageSpeed = averageSpeed;
        DLogger.v(TAG, "%d 个任务下载中, %d 个任务等待中", hawk.trace.concurrentThread.get(), hawk.trace.peddingThread.get());

        enqueueNotify(true);
    }

    private void handleUpdate(String key) {
        boolean isActive = false;

        do {
            Hawk hawk = Hawk.getInstance();
            if (hawk == null) {
                break;
            }

            // 处理是否还有下载
            synchronized (hawk.mRequestMap) {
                Request request = hawk.mRequestMap.get(key);
                if (request == null) {
                    request = hawk.db.query(key);
                    if (request != null) {
                        hawk.mRequestMap.put(key, request);
                    }
                }
                if (request == null)
                    break;

                // 新建下载
                if (request.isReadyToDownload()) {
                    // 如果正在等待下载，删除重连闹钟
                    if (request.downloadInfo.numFailed > 0) {
                        cancelRetryAlarm(request);
                    }

                    isActive = true;

                    mExecutor.execute(new DownloadThread(hawk, request, DownloadService.this));
                }
            }
        } while (false);

        if (!isActive) {
            stopIfNeed();
        }

        DLogger.v(TAG, "isActive = " + isActive + ", key = " + key);
    }

    void threadIncrement() {
        int count = mThreadCount.incrementAndGet();
        DLogger.v(TAG, "ThreadCount %d", count);
    }

    void threadDecrement() {
        int count = mThreadCount.decrementAndGet();
        DLogger.v(TAG, "ThreadCount %d", count);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        DLogger.w(TAG, "DownloadService onDestory");

        destoryResource();
    }

    private boolean isActive() {
        if (mThreadCount.get() > 0) {
            DLogger.i(TAG, "存在未下载完任务");

            return true;
        }

        return false;
    }

    void stopIfNeed() {
        if (isResourceDestoryed()) {
            return;
        }

        if (isActive()) {
            DLogger.i(TAG, "服务有未完成任务，不需要停止");
        }
        else {
            mHandler.post(new Runnable() {

                @Override
                public void run() {
                    handleNotify();

                    destoryResource();

                    DLogger.w(TAG, "stopSelf");
                    stopSelf();
                }

            });
        }
    }

    private boolean isResourceDestoryed() {
        return mResourceDestoryed.get();
    }

    private void destoryResource() {
        if (mResourceDestoryed.get()) {
            return;
        }
        mResourceDestoryed.set(true);

        try {
            DLogger.w(TAG, "DestoryResource");

            mExecutor.shutdownNow();
            mHandlerThread.quit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();

        DLogger.w(TAG, "Service finalize()");
    }

}
