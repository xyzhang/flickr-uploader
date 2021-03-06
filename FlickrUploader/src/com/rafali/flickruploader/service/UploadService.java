package com.rafali.flickruploader.service;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.LoggerFactory;

import se.emilsjolander.sprinkles.OneQuery;
import se.emilsjolander.sprinkles.Query;
import se.emilsjolander.sprinkles.Transaction;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;

import com.googlecode.androidannotations.api.BackgroundExecutor;
import com.rafali.common.ToolString;
import com.rafali.flickruploader.FlickrUploader;
import com.rafali.flickruploader.api.FlickrApi;
import com.rafali.flickruploader.broadcast.AlarmBroadcastReceiver;
import com.rafali.flickruploader.enums.CAN_UPLOAD;
import com.rafali.flickruploader.enums.MEDIA_TYPE;
import com.rafali.flickruploader.enums.STATUS;
import com.rafali.flickruploader.model.Folder;
import com.rafali.flickruploader.model.Media;
import com.rafali.flickruploader.tool.Notifications;
import com.rafali.flickruploader.tool.Utils;
import com.rafali.flickruploader.tool.Utils.Callback;
import com.rafali.flickruploader.ui.activity.FlickrUploaderActivity;

public class UploadService extends Service {

	static final org.slf4j.Logger LOG = LoggerFactory.getLogger(UploadService.class);

	private static final Set<UploadProgressListener> uploadProgressListeners = new HashSet<UploadService.UploadProgressListener>();

	public static interface UploadProgressListener {
		void onProgress(final Media media, final int mediaProgress, final int queueProgress, final int queueTotal);

		void onProcessed(final Media media, final boolean success);

		void onPaused();

		void onFinished(final int nbUploaded, final int nbErrors);

		void onQueued(final int nbQueued, final int nbAlreadyUploaded, final int nbAlreadyQueued);
	}

	public static void register(UploadProgressListener uploadProgressListener) {
		if (uploadProgressListener != null)
			uploadProgressListeners.add(uploadProgressListener);
		else
			LOG.warn("uploadProgressListener is null");
	}

	public static void unregister(UploadProgressListener uploadProgressListener) {
		if (uploadProgressListener != null)
			uploadProgressListeners.remove(uploadProgressListener);
		else
			LOG.warn("uploadProgressListener is null");
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private static UploadService instance;

	@Override
	public void onCreate() {
		super.onCreate();
		instance = this;
		LOG.debug("Service created ...");
		running = true;
		getContentResolver().registerContentObserver(Images.Media.EXTERNAL_CONTENT_URI, true, imageTableObserver);
		getContentResolver().registerContentObserver(Video.Media.EXTERNAL_CONTENT_URI, true, imageTableObserver);

		if (thread == null || !thread.isAlive()) {
			thread = new Thread(new UploadRunnable());
			thread.start();
		}
		IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		registerReceiver(batteryReceiver, filter);
		BackgroundExecutor.execute(new Runnable() {
			@Override
			public void run() {
				checkNewFiles();
			}
		});
		Notifications.init();
	}

	ContentObserver imageTableObserver = new ContentObserver(new Handler()) {
		@Override
		public void onChange(boolean change) {
			UploadService.checkNewFiles();
		}
	};

	BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
			boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
			Utils.setCharging(charging);
			// LOG.debug("charging : " + charging + ", status : " + status);
			if (charging)
				wake();
		}
	};

	private long started = System.currentTimeMillis();
	private boolean destroyed = false;

	@Override
	public void onDestroy() {
		super.onDestroy();
		destroyed = true;
		LOG.debug("Service destroyed ... started " + ToolString.formatDuration(System.currentTimeMillis() - started) + " ago");
		if (instance == this) {
			instance = null;
		}
		running = false;
		unregisterReceiver(batteryReceiver);
		getContentResolver().unregisterContentObserver(imageTableObserver);
	}

	static ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);
	static CheckNewFilesTask checkNewFilesTask;

	static class CheckNewFilesTask implements Runnable {

		ScheduledFuture<?> future;

		@Override
		public void run() {
			checkNewFiles();
		}

		void cancel(boolean mayInterruptIfRunning) {
			try {
				future.cancel(mayInterruptIfRunning);
			} catch (Throwable e) {
				LOG.error(ToolString.stack2string(e));
			}
		}

	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (Utils.canAutoUploadBool()) {
			// We want this service to continue running until it is explicitly
			// stopped, so return sticky.
			return START_STICKY;
		} else {
			return super.onStartCommand(intent, flags, startId);
		}
	}

	boolean running = false;

	public static int enqueue(boolean auto, Collection<Media> medias, String photoSetTitle) {
		int nbQueued = 0;
		int nbAlreadyQueued = 0;
		int nbAlreadyUploaded = 0;
		Transaction t = new Transaction();
		try {
			for (Media media : medias) {
				if (media.isQueued()) {
					nbAlreadyQueued++;
				} else if (media.isUploaded()) {
					nbAlreadyUploaded++;
				} else if (auto && media.getRetries() > 3) {
					LOG.debug("not auto enqueueing file with too many retries : " + media);
				} else {
					nbQueued++;
					LOG.debug("enqueueing " + media);
					media.setFlickrSetTitle(photoSetTitle);
					media.setStatus(STATUS.QUEUED, t);
				}
			}
			t.setSuccessful(true);
		} finally {
			t.finish();
		}
		for (UploadProgressListener uploadProgressListener : uploadProgressListeners) {
			uploadProgressListener.onQueued(nbQueued, nbAlreadyUploaded, nbAlreadyQueued);
		}
		wake(nbQueued > 0);
		return nbQueued;
	}

	public static void enqueueRetry(Iterable<Media> medias) {
		int nbQueued = 0;
		Transaction t = new Transaction();
		try {
			for (Media media : medias) {
				if (!media.isQueued() && !FlickrApi.unretryable.contains(media)) {
					nbQueued++;
					media.setStatus(STATUS.QUEUED, t);
				}
			}
			t.setSuccessful(true);
		} finally {
			t.finish();
		}
		wake(nbQueued > 0);
	}

	public static void dequeue(Collection<Media> medias) {
		Transaction t = new Transaction();
		try {
			for (Media media : medias) {
				if (media.isQueued()) {
					LOG.debug("dequeueing " + media);
					media.setStatus(STATUS.PAUSED, t);
				}
			}
			t.setSuccessful(true);
		} finally {
			t.finish();
		}
		wake();
	}

	private static boolean paused = true;

	public static boolean isPaused() {
		return paused;
	}

	private static Media mediaCurrentlyUploading;
	private static Media mediaPreviouslyUploading;
	private static long lastUpload = 0;

	public static Media getTopQueued() {
		OneQuery<Media> one = Query.one(Media.class, "select * from Media where status=? order by timestampCreated desc limit 1", STATUS.QUEUED);
		return one.get();
	}

	class UploadRunnable implements Runnable {
		@Override
		public void run() {
			int nbFail = 0;
			while (running) {
				try {
					mediaCurrentlyUploading = getTopQueued();
					LOG.info("mediaCurrentlyUploading : " + mediaCurrentlyUploading);
					CAN_UPLOAD canUploadNow = Utils.canUploadNow();
					if (mediaCurrentlyUploading == null || canUploadNow != CAN_UPLOAD.ok) {
						if (mediaCurrentlyUploading == null) {
							if (mediaPreviouslyUploading != null) {
								mediaPreviouslyUploading = null;
								onUploadFinished();
							}
						} else {
							for (UploadProgressListener uploadProgressListener : uploadProgressListeners) {
								uploadProgressListener.onPaused();
							}
						}

						paused = true;
						synchronized (mPauseLock) {
							// LOG.debug("waiting for work");
							if (mediaCurrentlyUploading == null) {
								if ((FlickrUploaderActivity.getInstance() == null || FlickrUploaderActivity.getInstance().isPaused()) && !Utils.canAutoUploadBool()
										&& System.currentTimeMillis() - lastUpload > 5 * 60 * 1000) {
									running = false;
									LOG.debug("stopping service after waiting for 5 minutes");
								} else {
									if (Utils.canAutoUploadBool()) {
										mPauseLock.wait();
									} else {
										LOG.debug("will stop the service if no more upload " + ToolString.formatDuration(System.currentTimeMillis() - started));
										mPauseLock.wait(60000);
									}
								}
							} else {
								if (FlickrUploaderActivity.getInstance() != null && !FlickrUploaderActivity.getInstance().isPaused()) {
									mPauseLock.wait(2000);
								} else {
									mPauseLock.wait(60000);
								}
							}
						}

					} else {
						paused = false;
						if (FlickrApi.isAuthentified()) {
							long start = System.currentTimeMillis();
							onUploadProgress(mediaCurrentlyUploading, 0);
							String photosetTitle = mediaCurrentlyUploading.getFlickrSetTitle();
							boolean success = mediaCurrentlyUploading.isUploaded();
							if (!success) {
								LOG.debug("Starting upload : " + mediaCurrentlyUploading);
								success = FlickrApi.upload(mediaCurrentlyUploading, photosetTitle);
							}
							long time = System.currentTimeMillis() - start;

							if (success) {
								lastUpload = System.currentTimeMillis();
								nbFail = 0;
								LOG.debug("Upload success : " + time + "ms " + mediaCurrentlyUploading);
								mediaCurrentlyUploading.setStatus(STATUS.UPLOADED, null);
							} else {
								mediaCurrentlyUploading.setStatus(STATUS.FAILED, null);
								if (FlickrApi.unretryable.contains(mediaCurrentlyUploading)) {
									mediaCurrentlyUploading.setRetries(3);
								} else {
									int retries = mediaCurrentlyUploading.getRetries();
									mediaCurrentlyUploading.setRetries(retries + 1);
									nbFail++;
									LOG.warn("Upload fail : nbFail=" + nbFail + " in " + time + "ms : " + mediaCurrentlyUploading);
									Thread.sleep(Math.min(20000, (long) (Math.pow(2, nbFail) * 2000)));
								}
							}
							for (UploadProgressListener uploadProgressListener : uploadProgressListeners) {
								uploadProgressListener.onProcessed(mediaCurrentlyUploading, success);
							}

						} else {
							Notifications.clear();
						}
					}

					FlickrUploader.cleanLogs();

				} catch (InterruptedException e) {
					LOG.warn("Thread interrupted");
				} catch (Throwable e) {
					LOG.error(ToolString.stack2string(e));
				} finally {
					if (mediaCurrentlyUploading != null) {
						mediaCurrentlyUploading.save();
						mediaPreviouslyUploading = mediaCurrentlyUploading;
						mediaCurrentlyUploading = null;
					}
				}
			}
			stopSelf();
		}
	}

	public static boolean isQueueEmpty() {
		OneQuery<Media> one = Query.one(Media.class, "select * from Media where (status=? or status=?) limit 1", STATUS.QUEUED, STATUS.FAILED);
		return one.get() == null;
	}

	public static void wake() {
		wake(false);
	}

	public static void wake(boolean force) {
		if ((instance == null || instance.destroyed) && (force || Utils.canAutoUploadBool() || !isQueueEmpty())) {
			Context context = FlickrUploader.getAppContext();
			context.startService(new Intent(context, UploadService.class));
			AlarmBroadcastReceiver.initAlarm();
		}
		synchronized (mPauseLock) {
			mPauseLock.notifyAll();
		}
	}

	private static final Object mPauseLock = new Object();

	private Thread thread;

	public static boolean isCurrentlyUploading(Media media) {
		if (media.equals(mediaCurrentlyUploading))
			return true;
		return false;
	}

	public static void updateProgressInfo(boolean force) {
		if (force || System.currentTimeMillis() - lastUpdate > 2000L) {
			lastUpdate = System.currentTimeMillis();
			List<Media> medias = Utils.loadMedia();
			long timestampQueued = 0;
			nbTotal = 0;
			nbUploaded = 0;
			nbFailed = 0;
			for (Media media : medias) {
				if (media.getTimestampQueued() > System.currentTimeMillis() - 24 * 3600 * 1000L && (media.isQueued() || media.isUploaded() || media.isFailed())) {
					if (timestampQueued <= 0 || media.isUploaded() && (media.getTimestampQueued() - timestampQueued) > 15 * 60 * 1000L) {
						nbTotal = 0;
						nbUploaded = 0;
						nbFailed = 0;
						timestampQueued = media.getTimestampQueued();
					}
					nbTotal++;
					if (media.isUploaded()) {
						nbUploaded++;
					} else if (media.isFailed()) {
						nbFailed++;
					}
				}
			}
		}
	}

	static int nbFailed;
	static int nbUploaded;
	static int nbTotal;
	static long lastUpdate = 0;

	public static void onUploadProgress(Media media, int mediaProgress) {
		for (UploadProgressListener uploadProgressListener : uploadProgressListeners) {
			updateProgressInfo(mediaProgress >= 100);
			uploadProgressListener.onProgress(media, mediaProgress, nbUploaded, nbTotal);
		}
	}

	public static void onUploadFinished() {
		for (UploadProgressListener uploadProgressListener : uploadProgressListeners) {
			updateProgressInfo(true);
			uploadProgressListener.onFinished(nbUploaded, nbFailed);
		}
	}

	public static void clear(final int status, final Callback<Void> callback) {
		if (status == STATUS.FAILED || status == STATUS.QUEUED) {
			BackgroundExecutor.execute(new Runnable() {
				@Override
				public void run() {
					List<Media> medias = Utils.loadMedia();
					Transaction t = new Transaction();
					try {
						for (Media media : medias) {
							if (media.getStatus() == status) {
								media.setStatus(STATUS.PAUSED, t);
							}
						}
						t.setSuccessful(true);
					} finally {
						t.finish();
					}
					if (callback != null)
						callback.onResult(null);
				}
			});
		} else {
			LOG.error("status " + status + " is not supported");
		}
	}

	public static void checkNewFiles() {
		try {
			String canAutoUpload = Utils.canAutoUpload();
			if (!"true".equals(canAutoUpload)) {
				LOG.info("canAutoUpload : " + canAutoUpload);
				return;
			}

			List<Media> medias = Utils.loadMedia();

			if (medias == null || medias.isEmpty()) {
				LOG.info("no media found");
				return;
			}

			Map<String, Folder> pathFolders = Utils.getFolders(false);

			if (pathFolders.isEmpty()) {
				LOG.info("no folder monitored");
				return;
			}

			long uploadDelayMs = Utils.getUploadDelayMs();
			long newestFileAge = 0;
			for (Media media : medias) {
				if (media.isImported()) {
					if (media.getMediaType() == MEDIA_TYPE.PHOTO && !Utils.isAutoUpload(MEDIA_TYPE.PHOTO)) {
						LOG.debug("not uploading " + media + " because photo upload disabled");
						continue;
					} else if (media.getMediaType() == MEDIA_TYPE.VIDEO && !Utils.isAutoUpload(MEDIA_TYPE.VIDEO)) {
						LOG.debug("not uploading " + media + " because video upload disabled");
						continue;
					} else {
						File file = new File(media.getPath());
						if (file.exists()) {
							boolean uploaded = media.isUploaded();
							LOG.debug("uploaded : " + uploaded + ", " + media);
							if (!uploaded) {
								Folder folder = pathFolders.get(media.getFolderPath());
								if (folder == null || !folder.isAutoUploaded()) {
									LOG.debug(media.getFolderPath() + " not monitored : " + file);
								} else {
									int sleep = 0;
									while (file.length() < 100 && sleep < 5) {
										LOG.debug("sleeping a bit");
										sleep++;
										Thread.sleep(1000);
									}
									long fileAge = System.currentTimeMillis() - file.lastModified();
									LOG.debug("uploadDelayMs:" + uploadDelayMs + ", fileAge:" + fileAge + ", newestFileAge:" + newestFileAge);
									if (uploadDelayMs > 0 && fileAge < uploadDelayMs) {
										if (newestFileAge < fileAge) {
											newestFileAge = fileAge;
											long delay = Math.max(1000, uploadDelayMs - newestFileAge);
											LOG.debug("waiting " + ToolString.formatDuration(delay) + " for the " + ToolString.formatDuration(uploadDelayMs) + " delay");
											if (checkNewFilesTask != null) {
												checkNewFilesTask.cancel(false);
											}
											checkNewFilesTask = new CheckNewFilesTask();
											checkNewFilesTask.future = scheduledThreadPoolExecutor.schedule(checkNewFilesTask, delay, TimeUnit.MILLISECONDS);
										}
									} else {
										enqueue(true, Arrays.asList(media), folder.getFlickrSetTitle());
									}
								}
							}
						} else {
							LOG.debug("Deleted : " + file);
							media.deleteAsync();
						}
					}
				}
			}
		} catch (Throwable e) {
			LOG.error(ToolString.stack2string(e));
		}
	}

	public static int getNbQueued() {
		updateProgressInfo(false);
		return nbTotal - nbUploaded - nbFailed;
	}

	public static int getNbUploadedTotal() {
		updateProgressInfo(false);
		return nbUploaded;
	}

	public static int getNbError() {
		updateProgressInfo(false);
		return nbFailed;
	}
}