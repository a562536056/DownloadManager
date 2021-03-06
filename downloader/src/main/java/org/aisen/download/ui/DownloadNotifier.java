package org.aisen.download.ui;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import org.aisen.download.R;
import org.aisen.download.core.DownloadInfo;
import org.aisen.download.core.Downloads;
import org.aisen.download.utils.Constants;
import org.aisen.download.utils.DLogger;
import org.aisen.download.utils.LongSparseLongArray;

import java.text.NumberFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import static android.app.DownloadManager.Request.VISIBILITY_VISIBLE;
import static android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED;
import static android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION;
import static org.aisen.download.core.Downloads.Impl.STATUS_RUNNING;

/**
 * Created by wangdan on 16/7/30.
 */
public class DownloadNotifier {

    static final String TAG = Constants.TAG + "_DownloadNotifier";

    private static final int TYPE_ACTIVE = 1;
    private static final int TYPE_WAITING = 2;
    private static final int TYPE_COMPLETE = 3;

    private final Context mContext;
    private final NotificationManager mNotifManager;

    /**
     * Currently active notifications, mapped from clustering tag to timestamp
     * when first shown.
     *
     */
    private final HashMap<String, Long> mActiveNotifs = new HashMap<>();

    /**
     * Current speed of active downloads, mapped from {@link DownloadInfo#mId}
     * to speed in bytes per second.
     */
    private final LongSparseLongArray mDownloadSpeed = new LongSparseLongArray();

    /**
     * Last time speed was reproted, mapped from {@link DownloadInfo#mId} to
     * {@link SystemClock#elapsedRealtime()}.
     */
    private final LongSparseLongArray mDownloadTouch = new LongSparseLongArray();

    public DownloadNotifier(Context context) {
        mContext = context;
        mNotifManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
    }

    public void cancelAll() {
        mNotifManager.cancelAll();
    }

    /**
     * Notify the current speed of an active download, used for calculating
     * estimated remaining time.
     */
    public void notifyDownloadSpeed(long id, long bytesPerSecond) {
        DLogger.v(TAG, "notifyDownloadSpeed(id = %s, bytesPerSecond = %s)", id + "", bytesPerSecond + "");

        synchronized (mDownloadSpeed) {
            if (bytesPerSecond != 0) {
                mDownloadSpeed.put(id, bytesPerSecond);
                mDownloadTouch.put(id, SystemClock.elapsedRealtime());
            } else {
                mDownloadSpeed.delete(id);
                mDownloadTouch.delete(id);
            }
        }
    }

    /**
     * Update {@link NotificationManager} to reflect the given set of
     * {@link DownloadInfo}, adding, collapsing, and removing as needed.
     */
    public void updateWith(Collection<DownloadInfo> downloads) {
        for (DownloadInfo info : downloads) {
            DLogger.v(TAG, "updateWith, Info[%s], status[%s]",
                    info.mTitle,
                    Downloads.Impl.statusToString(info.mStatus));
        }

        synchronized (mActiveNotifs) {
            updateWithLocked(downloads);
        }
    }

    private void updateWithLocked(Collection<DownloadInfo> downloads) {
        final Resources res = mContext.getResources();

        // Cluster downloads together
        final Multimap<String, DownloadInfo> clustered = ArrayListMultimap.create();
        for (DownloadInfo info : downloads) {
            final String tag = buildNotificationTag(info, mContext);
            if (tag != null) {
                DLogger.d(TAG, "buildNotificationTag, Download[%s], status[%d] , tag[%s]", info.mTitle, info.mStatus, tag + "");

                clustered.put(tag, info);
            }
        }

        // Build notification for each cluster
        for (String tag : clustered.keySet()) {
            final int type = getNotificationTagType(tag);
            final Collection<DownloadInfo> cluster = clustered.get(tag);

            final NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext);
            builder.setColor(res.getColor(
                    R.color.system_notification_accent_color));

            // Use time when cluster was first shown to avoid shuffling
            final long firstShown;
            if (mActiveNotifs.containsKey(tag)) {
                firstShown = mActiveNotifs.get(tag);
            } else {
                firstShown = System.currentTimeMillis();
                mActiveNotifs.put(tag, firstShown);
            }
            builder.setWhen(firstShown);

            // Show relevant icon
            if (type == TYPE_ACTIVE) {
                builder.setSmallIcon(android.R.drawable.stat_sys_download);
            } else if (type == TYPE_WAITING) {
                builder.setSmallIcon(android.R.drawable.stat_sys_warning);
            } else if (type == TYPE_COMPLETE) {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done);
            }

            // Build action intents
//            if (type == TYPE_ACTIVE || type == TYPE_WAITING) {
//                // build a synthetic uri for intent identification purposes
//                final Uri uri = new Uri.Builder().scheme("active-dl").appendPath(tag).build();
//                final Intent intent = new Intent(Constants.ACTION_LIST,
//                        uri, mContext, DownloadReceiver.class);
//                intent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
//                        getDownloadIds(cluster));
//                builder.setContentIntent(PendingIntent.getBroadcast(mContext,
//                        0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
//                builder.setOngoing(true);
//
//            } else if (type == TYPE_COMPLETE) {
//                final DownloadInfo info = cluster.iterator().next();
//                final Uri uri = ContentUris.withAppendedId(
//                        Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, info.mId);
//                builder.setAutoCancel(true);
//
//                final String action;
//                if (Downloads.Impl.isStatusError(info.mStatus)) {
//                    action = Constants.ACTION_LIST;
//                } else {
//                    if (info.mDestination != Downloads.Impl.DESTINATION_SYSTEMCACHE_PARTITION) {
//                        action = Constants.ACTION_OPEN;
//                    } else {
//                        action = Constants.ACTION_LIST;
//                    }
//                }
//
//                final Intent intent = new Intent(action, uri, mContext, DownloadReceiver.class);
//                intent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
//                        getDownloadIds(cluster));
//                builder.setContentIntent(PendingIntent.getBroadcast(mContext,
//                        0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
//
//                final Intent hideIntent = new Intent(Constants.ACTION_HIDE,
//                        uri, mContext, DownloadReceiver.class);
//                builder.setDeleteIntent(PendingIntent.getBroadcast(mContext, 0, hideIntent, 0));
//            }

            // Calculate and show progress
            String remainingText = null;
            String percentText = null;
            if (type == TYPE_ACTIVE) {
                long current = 0;
                long total = 0;
                long speed = 0;
                synchronized (mDownloadSpeed) {
                    for (DownloadInfo info : cluster) {
                        if (info.mTotalBytes != -1) {
                            current += info.mCurrentBytes;
                            total += info.mTotalBytes;
                            speed += mDownloadSpeed.get(info.mId);
                        }
                    }
                }

                if (total > 0) {
                    percentText =
                            NumberFormat.getPercentInstance().format((double) current / total);

                    if (speed > 0) {
                        final long remainingMillis = ((total - current) * 1000) / speed;
                        remainingText = res.getString(R.string.download_remaining, formatDuration(remainingMillis, mContext.getResources()));
                    }

                    final int percent = (int) ((current * 100) / total);
                    builder.setProgress(100, percent, false);
                } else {
                    builder.setProgress(100, 0, true);
                }
            }

            // Build titles and description
            final Notification notif;
            if (cluster.size() == 1) {
                final DownloadInfo info = cluster.iterator().next();

                builder.setContentTitle(getDownloadTitle(res, info));

                if (type == TYPE_ACTIVE) {
                    if (!TextUtils.isEmpty(info.mDescription)) {
                        builder.setContentText(info.mDescription);
                    } else {
                        builder.setContentText(remainingText);
                    }
                    builder.setContentInfo(percentText);

                } else if (type == TYPE_WAITING) {
                    builder.setContentText(
                            res.getString(R.string.notification_need_wifi_for_size));

                } else if (type == TYPE_COMPLETE) {
                    if (Downloads.Impl.isStatusError(info.mStatus)) {
                        builder.setContentText(res.getText(R.string.notification_download_failed));
                    } else if (Downloads.Impl.isStatusSuccess(info.mStatus)) {
                        builder.setContentText(
                                res.getText(R.string.notification_download_complete));
                    }
                }

                notif = builder.build();

            } else {
                final NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle(builder);

                for (DownloadInfo info : cluster) {
                    inboxStyle.addLine(getDownloadTitle(res, info));
                }

                if (type == TYPE_ACTIVE) {
                    builder.setContentTitle(res.getQuantityString(
                            R.plurals.notif_summary_active, cluster.size(), cluster.size()));
                    builder.setContentText(remainingText);
                    builder.setContentInfo(percentText);
                    inboxStyle.setSummaryText(remainingText);

                } else if (type == TYPE_WAITING) {
                    builder.setContentTitle(res.getQuantityString(
                            R.plurals.notif_summary_waiting, cluster.size(), cluster.size()));
                    builder.setContentText(
                            res.getString(R.string.notification_need_wifi_for_size));
                    inboxStyle.setSummaryText(
                            res.getString(R.string.notification_need_wifi_for_size));
                }

                notif = inboxStyle.build();
            }

            mNotifManager.notify(tag, 0, notif);
        }

        // Remove stale tags that weren't renewed
        final Iterator<String> it = mActiveNotifs.keySet().iterator();
        while (it.hasNext()) {
            final String tag = it.next();
            if (!clustered.containsKey(tag)) {
                mNotifManager.cancel(tag, 0);
                it.remove();
            }
        }
    }

    private static CharSequence getDownloadTitle(Resources res, DownloadInfo info) {
        if (!TextUtils.isEmpty(info.mTitle)) {
            return info.mTitle;
        } else {
            return res.getString(R.string.download_unknown_title);
        }
    }

    private long[] getDownloadIds(Collection<DownloadInfo> infos) {
        final long[] ids = new long[infos.size()];
        int i = 0;
        for (DownloadInfo info : infos) {
            ids[i++] = info.mId;
        }
        return ids;
    }

    public void dumpSpeeds() {
        synchronized (mDownloadSpeed) {
            for (int i = 0; i < mDownloadSpeed.size(); i++) {
                final long id = mDownloadSpeed.keyAt(i);
                final long delta = SystemClock.elapsedRealtime() - mDownloadTouch.get(id);
                DLogger.d(TAG, "Download " + id + " speed " + mDownloadSpeed.valueAt(i) + "bps, "
                        + delta + "ms ago");
            }
        }
    }

    /**
     * Build tag used for collapsing several {@link DownloadInfo} into a single
     * {@link Notification}.
     */
    private static String buildNotificationTag(DownloadInfo info, Context context) {
        if (info.mStatus == Downloads.Impl.STATUS_QUEUED_FOR_WIFI) {
            return TYPE_WAITING + ":" + context.getPackageName();
        } else if (isActiveAndVisible(info)) {
            return TYPE_ACTIVE + ":" + context.getPackageName();
        } else if (isCompleteAndVisible(info)) {
            // Complete downloads always have unique notifs
            return TYPE_COMPLETE + ":" + info.mId;
        } else {
            return null;
        }
    }

    /**
     * Return the cluster type of the given tag, as created by
     */
    private static int getNotificationTagType(String tag) {
        return Integer.parseInt(tag.substring(0, tag.indexOf(':')));
    }

    private static boolean isActiveAndVisible(DownloadInfo download) {
        return download.mStatus == STATUS_RUNNING &&
                (download.mVisibility == VISIBILITY_VISIBLE
                        || download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
    }

    private static boolean isCompleteAndVisible(DownloadInfo download) {
        return Downloads.Impl.isStatusCompleted(download.mStatus) &&
                (download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        || download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION);
    }

    public static CharSequence formatDuration(long millis, Resources res) {
//        final Resources res = Resources.getSystem();
        if (millis >= DateUtils.HOUR_IN_MILLIS) {
            final int hours = (int) ((millis + 1800000) / DateUtils.HOUR_IN_MILLIS);
            return res.getQuantityString(
                    R.plurals.duration_hours, hours, hours);
        } else if (millis >= DateUtils.MINUTE_IN_MILLIS) {
            final int minutes = (int) ((millis + 30000) / DateUtils.MINUTE_IN_MILLIS);
            return res.getQuantityString(
                    R.plurals.duration_minutes, minutes, minutes);
        } else {
            final int seconds = (int) ((millis + 500) / DateUtils.SECOND_IN_MILLIS);
            return res.getQuantityString(
                    R.plurals.duration_seconds, seconds, seconds);
        }
    }

}
