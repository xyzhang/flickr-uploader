package com.rafali.flickruploader.api;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.LoggerFactory;

import se.emilsjolander.sprinkles.CursorList;
import se.emilsjolander.sprinkles.Query;
import se.emilsjolander.sprinkles.Transaction;
import android.content.Context;
import android.net.ConnectivityManager;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.googlecode.flickrjandroid.Flickr;
import com.googlecode.flickrjandroid.FlickrException;
import com.googlecode.flickrjandroid.RequestContext;
import com.googlecode.flickrjandroid.oauth.OAuth;
import com.googlecode.flickrjandroid.oauth.OAuthToken;
import com.googlecode.flickrjandroid.photos.Photo;
import com.googlecode.flickrjandroid.photos.PhotoList;
import com.googlecode.flickrjandroid.photos.SearchParameters;
import com.googlecode.flickrjandroid.photosets.Photoset;
import com.googlecode.flickrjandroid.tags.Tag;
import com.googlecode.flickrjandroid.uploader.UploadMetaData;
import com.rafali.common.STR;
import com.rafali.common.ToolString;
import com.rafali.flickruploader.FlickrUploader;
import com.rafali.flickruploader.enums.CAN_UPLOAD;
import com.rafali.flickruploader.enums.PRIVACY;
import com.rafali.flickruploader.model.FlickrSet;
import com.rafali.flickruploader.model.Media;
import com.rafali.flickruploader.tool.Utils;
import com.rafali.flickruploader.ui.activity.PreferencesActivity;
import com.rafali.flickruploader2.R;

public class FlickrApi {
	static final org.slf4j.Logger LOG = LoggerFactory.getLogger(FlickrApi.class);

	private static final HashSet<String> EXTRAS_MACHINE_TAGS = Sets.newHashSet("machine_tags", "date_upload");

	private static final String API_SECRET = Utils.getString(R.string.flickr_api_secret);
	private static final String API_KEY = Utils.getString(R.string.flickr_api_key);
	private static Flickr flickr = new Flickr(API_KEY, API_SECRET);

	private static OAuth auth;

	public static Flickr get() {
		if (auth == null) {
			updateOauth();
		}
		if (RequestContext.getRequestContext().getOAuth() != auth) {
			LOG.info(Thread.currentThread() + " : " + RequestContext.getRequestContext() + " : " + RequestContext.getRequestContext().getOAuth() + " : " + auth);
			RequestContext.getRequestContext().setOAuth(auth);
		}
		return flickr;
	}

	static ExecutorService executorService = Executors.newSingleThreadExecutor();

	public static void reset() {
		auth = null;
		updateOauth();
	}

	private static void updateOauth() {
		String accessToken = Utils.getStringProperty(STR.accessToken);
		String accessTokenSecret = Utils.getStringProperty(STR.accessTokenSecret);
		authentified = ToolString.isNotBlank(accessToken) && ToolString.isNotBlank(accessTokenSecret);
		auth = new OAuth();
		auth.setToken(new OAuthToken(accessToken, accessTokenSecret));
	}

	private static boolean authentified = false;

	public static Set<Media> unretryable = new HashSet<Media>();

	static PRIVACY getPrivacy(Photo photo) {
		if (photo.isPublicFlag()) {
			return PRIVACY.PUBLIC;
		} else if (photo.isFamilyFlag() && photo.isFriendFlag()) {
			return PRIVACY.FRIENDS_FAMILY;
		} else if (photo.isFamilyFlag()) {
			return PRIVACY.FAMILY;
		} else if (photo.isFriendFlag()) {
			return PRIVACY.FRIENDS;
		}
		return PRIVACY.PRIVATE;
	}

	private static long lastSyncMedia = 0;

	public static void syncMedia() {
		if (System.currentTimeMillis() - lastSyncMedia > 10 * 60 * 1000L) {
			lastSyncMedia = System.currentTimeMillis();
			executorService.execute(new Runnable() {
				@Override
				public void run() {
					try {
						List<Media> medias = Utils.loadMedia();
						Collections.reverse(medias);
						final Map<String, Media> hashMedia = new ConcurrentHashMap<String, Media>();
						for (Media media : medias) {
							hashMedia.put(media.getSha1Tag(), media);
						}
						final Map<String, String> uploadedPhotos = new ConcurrentHashMap<String, String>();
						final Map<String, PRIVACY> photosPrivacy = new ConcurrentHashMap<String, PRIVACY>();
						int totalPage = 10;
						int page = 1;
						int per_page = 100;
						int count = 0;

						// fetching all uploaded photos
						// the flickr query is not consistent (not all
						// photos are retrieved)
						while (page <= totalPage) {
							SearchParameters params = new SearchParameters();
							params.setUserId(Utils.getStringProperty(STR.userId));
							params.setMachineTags(new String[] { "file:sha1sig=" });
							params.setSort(SearchParameters.DATE_POSTED_DESC);
							params.setExtras(EXTRAS_MACHINE_TAGS);
							final PhotoList photoList = FlickrApi.get().getPhotosInterface().search(params, per_page, page);
							totalPage = photoList.getPages();
							per_page = photoList.getPerPage();
							count += photoList.size();
							if (!photoList.isEmpty()) {
								Transaction t = new Transaction();
								LOG.debug(count + " photos with machine tag fetched. page:" + page + "/" + totalPage);
								try {
									for (Photo photo : photoList) {
										for (String tag : photo.getMachineTags()) {
											if (tag.startsWith("file:sha1sig")) {
												String flickrId = photo.getId();
												uploadedPhotos.put(tag, flickrId);
												photosPrivacy.put(flickrId, getPrivacy(photo));
												Media media = hashMedia.get(tag);
												if (media != null) {
													media.setFlickrId(flickrId);
													media.setTimestampUploaded(photo.getDatePosted());
													media.setPrivacy(photosPrivacy.get(flickrId));
													media.save2(t);
												}
											}
										}
									}
									t.setSuccessful(true);
								} catch (Throwable e) {
									LOG.error(ToolString.stack2string(e));
								} finally {
									t.finish();
								}

								page++;
							} else {
								break;
							}
						}
						for (Media media : medias) {
							String persistedFlickrId = media.getFlickrId();
							String flickrId = uploadedPhotos.get(media.getSha1Tag());
							if (persistedFlickrId != null && flickrId == null) {
								// as all photos may not have been retrieved, we need to check which one
								// have really been deleted manually
								try {
									Photo photo = FlickrApi.get().getPhotosInterface().getPhoto(persistedFlickrId);
									if (photo != null) {
										LOG.debug(persistedFlickrId + "=" + media.getSha1Tag() + " still exist");
										media.setPrivacy(photosPrivacy.get(flickrId));
										media.save();
									}
								} catch (FlickrException e) {
									// Photo not found
									if ("1".equals(e.getErrorCode())) {
										LOG.debug(flickrId + "=" + media.getSha1Tag() + " no longer still exist");
										media.setFlickrId(null);
										media.setPrivacy(null);
										media.save();
									} else {
										LOG.error(ToolString.stack2string(e));
									}
								} catch (Throwable e) {
									LOG.error(ToolString.stack2string(e));
								}

							}
						}

					} catch (Throwable e) {
						LOG.error(ToolString.stack2string(e));
					}
				}
			});
		}
		executorService.execute(new Runnable() {
			@Override
			public void run() {
				getPhotoSets(true);
			}
		});
	}

	@SuppressWarnings("deprecation")
	public static boolean upload(Media media, String photosetTitle) {
		if (photosetTitle == null) {
			LOG.warn("photosetTitle should not be null here, setting it to default " + STR.instantUpload);
			photosetTitle = STR.instantUpload;
		}
		boolean success = false;
		int retry = 0;
		String photoId = null;
		String sha1tag = media.getSha1Tag();
		ConnectivityManager cm = (ConnectivityManager) FlickrUploader.getAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);
		if (STR.wifionly.equals(Utils.getStringProperty(PreferencesActivity.UPLOAD_NETWORK))) {
			cm.setNetworkPreference(ConnectivityManager.TYPE_WIFI);
		} else {
			cm.setNetworkPreference(ConnectivityManager.DEFAULT_NETWORK_PREFERENCE);
		}
		String photosetId = null;
		while (retry < NB_RETRY && !success) {
			photosetId = getPhotoSetId(photosetTitle);
			try {
				if (photoId == null) {
					String extension = getExtension(media);
					if (unsupportedExtensions.contains(extension)) {
						throw new UploadException("Unsupported extension: " + extension);
					}
					String uri = media.getPath();
					File file = new File(uri);
					if (!file.exists()) {
						throw new UploadException("File no longer exists: " + file.getAbsolutePath());
					}
					if (file.length() <= 10) {
						throw new UploadException("File is empty: " + file.getAbsolutePath());
					}
					if (file.length() > 1024 * 1024 * 1024L) {
						throw new UploadException("File too big: " + file.getAbsolutePath());
					}
					String md5tag = media.getMd5Tag();
					SearchParameters params = new SearchParameters();
					params.setUserId(Utils.getStringProperty(STR.userId));
					params.setMachineTags(new String[] { md5tag });
					PhotoList photoList = FlickrApi.get().getPhotosInterface().search(params, 1, 1);
					if (!photoList.isEmpty()) {
						LOG.warn("already uploaded : " + photoList.get(0).getId() + " = " + md5tag + " = " + uri);
						Photo photo = photoList.get(0);
						photoId = photo.getId();
						photo = FlickrApi.get().getPhotosInterface().getInfo(photoId, photo.getSecret());
						if (photo != null) {
							List<String> tagstr = new ArrayList<String>();
							Collection<Tag> tags = photo.getTags();
							for (Tag tag : tags) {
								String value = tag.getValue();
								if (value.startsWith("file:sha1sig=") && !value.equals(sha1tag)) {
								} else {
									tagstr.add(value);
								}
								if (!tagstr.contains(sha1tag)) {
									tagstr.add(sha1tag);
									FlickrApi.get().getPhotosInterface().setTags(photoId, tagstr.toArray(new String[tagstr.size()]));
								}
							}
						}
					} else {
						if (Utils.canUploadNow() != CAN_UPLOAD.ok)
							break;
						LOG.debug("uploading : " + uri);
						// InputStream inputStream = new FileInputStream(uri);
						UploadMetaData metaData = new UploadMetaData();
						String uploadDescription = Utils.getUploadDescription();
						if (ToolString.isNotBlank(uploadDescription)) {
							metaData.setDescription(uploadDescription);
						}
						PRIVACY privacy = Utils.getDefaultPrivacy();
						metaData.setFriendFlag(privacy == PRIVACY.FRIENDS || privacy == PRIVACY.FRIENDS_FAMILY);
						metaData.setFamilyFlag(privacy == PRIVACY.FAMILY || privacy == PRIVACY.FRIENDS_FAMILY);
						metaData.setPublicFlag(privacy == PRIVACY.PUBLIC);
						List<String> tags = Lists.newArrayList(md5tag, sha1tag);
						String custom_tags = Utils.getStringProperty("custom_tags");
						if (ToolString.isNotBlank(custom_tags)) {
							tags.add(custom_tags);
						}
						metaData.setTags(tags);
						long start = System.currentTimeMillis();
						photoId = FlickrApi.get().getUploader().upload(media.getName(), file, metaData, media);
						LOG.debug("photo uploaded in " + (System.currentTimeMillis() - start) + "ms : " + photoId);
					}
				}
				if (photoId != null) {
					try {
						if (ToolString.isBlank(photosetId)) {
							Photoset photoset = FlickrApi.get().getPhotosetsInterface().create(photosetTitle, Utils.getUploadDescription(), photoId);
							photosetId = photoset.getId();
							cachedPhotoSets = null;
						} else {
							FlickrApi.get().getPhotosetsInterface().addPhoto(photosetId, photoId);
						}
					} catch (FlickrException fe) {
						if ("1".equals(fe.getErrorCode())) {// Photoset not found
							LOG.warn("photosetId : " + photosetId + " not found, photo will not be saved in a set");
							cachedPhotoSets = null;
						} else {
							throw fe;
						}
					}
				}
				success = true;
				exceptions.remove(media);
			} catch (Throwable e) {
				exceptions.put(media, e);
				if (e instanceof FlickrException) {
					FlickrException fe = (FlickrException) e;
					LOG.warn("retry " + retry + " : " + fe.getErrorCode() + " : " + fe.getErrorMessage());
					if ("1".equals(fe.getErrorCode())) {// Photoset not found
						LOG.warn("photosetId : " + photosetId + " not found");
						cachedPhotoSets = null;
					} else if ("3".equals(fe.getErrorCode())) {// Photo already
																// in set
						success = true;
					} else if ("98".equals(fe.getErrorCode())) {
						auth = null;
						authentified = false;
					} else if ("5".equals(fe.getErrorCode())) {
						addUnsupportedExtension(getExtension(media));
					}
				}
				LOG.error(ToolString.stack2string(e));
				if (!success) {
					if (isRetryable(e)) {
						LOG.error("retry " + retry + " : " + e.getClass().getSimpleName() + " : " + e.getMessage() + ", cause : " + e.getCause());
						try {
							Thread.sleep((long) (Math.pow(2, retry) * 1000));
						} catch (InterruptedException ignore) {
						}
					} else {
						unretryable.add(media);
						LOG.warn("not retrying : " + e.getClass().getSimpleName() + " : " + e.getMessage() + ", cause : " + e.getCause());
						break;
					}
				}
			} finally {
				retry++;
			}
		}
		if (photoId != null) {
			media.setFlickrId(photoId);
			if (success) {
				media.setFlickrSetId(photosetId);
			}
			media.save();
		}
		return success;
	}

	private static String getPhotoSetId(String photosetTitle) {
		if (photosetTitle != null) {
			Set<FlickrSet> photoSets = getPhotoSets(false);
			for (FlickrSet flickrSet : photoSets) {
				if (flickrSet.getName().equals(photosetTitle)) {
					return flickrSet.getId();
				}
			}
		}
		return null;
	}

	static String getExtension(Media media) {
		try {
			File file = new File(media.getPath());
			int lastIndexOf = file.getName().lastIndexOf('.');
			if (lastIndexOf > 0) {
				return file.getName().substring(lastIndexOf + 1).trim().toLowerCase(Locale.US);
			}
		} catch (Exception e1) {
			LOG.error(e1.getClass().getSimpleName() + " : " + e1.getMessage());
		}
		return null;
	}

	static Set<String> unsupportedExtensions = Sets.newHashSet("skm", "mkv");
	static Set<String> whitelistedExtensions = Sets.newHashSet("jpg", "png", "jpeg", "gif", "tiff", "avi", "wmv", "mov", "mpeg", "3gp", "m2ts", "ogg", "ogv");

	static Map<String, Integer> nbUnsupportedExtensions;

	static void addUnsupportedExtension(String extension) {
		if (extension != null && !whitelistedExtensions.contains(extension) && !unsupportedExtensions.contains(extension)) {
			if (nbUnsupportedExtensions == null) {
				nbUnsupportedExtensions = new HashMap<String, Integer>();
			}
			Integer nb = nbUnsupportedExtensions.get(extension);
			if (nb == null) {
				nb = 0;
			}
			if (nb > 1) {
				unsupportedExtensions.add(extension);
			}
			nbUnsupportedExtensions.put(extension, nb + 1);
		}
	}

	public static class UploadException extends Exception {
		private static final long serialVersionUID = 1L;

		public UploadException(String message) {
			super(message);
		}
	}

	public static Throwable getLastException(Media media) {
		return exceptions.get(media);
	}

	static boolean isRetryable(Throwable e) {
		if (e instanceof UploadException) {
			return false;
		} else if (e instanceof FlickrException) {
			return false;
		} else if (e instanceof FileNotFoundException) {
			return false;
		}
		if (e instanceof RuntimeException && e.getCause() != null) {
			return isRetryable(e.getCause());
		}
		return true;
	}

	private static Map<Media, Throwable> exceptions = new HashMap<Media, Throwable>();

	public static boolean isAuthentified() {
		if (auth == null) {
			updateOauth();
		}
		return authentified;
	}

	protected static String alphabetString = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
	protected static char[] alphabet = alphabetString.toCharArray();
	protected static int base_count = alphabet.length;
	public static final String FLICKR_SHORT_URL_PREFIX = "http://flic.kr/p/";

	public static final int NB_RETRY = 3;

	public static String getShortUrl(String id) {
		String suffix = encode(Long.parseLong(id));
		return FLICKR_SHORT_URL_PREFIX + suffix;
	}

	public static String encode(long num) {
		String result = "";
		long div;
		int mod = 0;

		while (num >= base_count) {
			div = num / base_count;
			mod = (int) (num - (base_count * (long) div));
			result = alphabet[mod] + result;
			num = (long) div;
		}
		if (num > 0) {
			result = alphabet[(int) num] + result;
		}
		return result;
	}

	static Set<FlickrSet> cachedPhotoSets;

	public static Set<FlickrSet> getPhotoSets(boolean refresh) {
		Set<FlickrSet> photoSets = new HashSet<FlickrSet>();
		try {
			CursorList<FlickrSet> cursorList = null;
			if (cachedPhotoSets == null) {
				cachedPhotoSets = new HashSet<FlickrSet>();
				cursorList = Query.all(FlickrSet.class).get();
				for (FlickrSet flickrSet : cursorList) {
					cachedPhotoSets.add(flickrSet);
				}
			}
			if (FlickrApi.isAuthentified() && (refresh || cachedPhotoSets.isEmpty())) {
				Map<String, FlickrSet> freshPhotoSets = new HashMap<String, FlickrSet>();
				// Log.i(TAG, "persisted uploadedPhotos : " + uploadedPhotos);
				Collection<Photoset> photosets = FlickrApi.get().getPhotosetsInterface().getList(Utils.getStringProperty(STR.userId)).getPhotosets();
				if (!photosets.isEmpty()) {
					Transaction t = new Transaction();
					try {
						for (final Photoset photoset : photosets) {
							FlickrSet flickrSet = new FlickrSet(photoset.getId(), photoset.getTitle());
							flickrSet.setSize(photoset.getPhotoCount());
							flickrSet.save(t);
							freshPhotoSets.put(photoset.getId(), flickrSet);
						}
						if (cursorList == null) {
							cursorList = Query.all(FlickrSet.class).get();
						}
						for (FlickrSet flickrSetName : cursorList) {
							if (!freshPhotoSets.containsKey(flickrSetName.getId())) {
								flickrSetName.delete(t);
							}
						}

						t.setSuccessful(true);
					} catch (Throwable e) {
						LOG.error(ToolString.stack2string(e));
					} finally {
						t.finish();
					}
				}
				cachedPhotoSets.clear();
				cachedPhotoSets.addAll(freshPhotoSets.values());
			}
			photoSets.addAll(cachedPhotoSets);
		} catch (Throwable e) {
			LOG.error(ToolString.stack2string(e));
		}
		return photoSets;
	}

	// private static void getSetPhotos(final String flickrSetId) {
	// executorService.execute(new Runnable() {
	// @Override
	// public void run() {
	// int page = 1;
	// int perPage = 500;
	// int totalPage = 10;
	// int nb = 0;
	// while (page <= totalPage) {
	// Transaction t = new Transaction();
	// try {
	// PhotoList photoList = FlickrApi.get().getPhotosetsInterface().getPhotos(flickrSetId, perPage, page);
	// page++;
	// totalPage = photoList.getPages();
	// perPage = photoList.getPerPage();
	// for (Photo photo : photoList) {
	// FlickrSetPhotos flickrPhotoSet = new FlickrSetPhotos(photo.getId(), flickrSetId);
	// flickrPhotoSet.save(t);
	// nb++;
	// }
	// t.setSuccessful(true);
	// } catch (Throwable e) {
	// LOG.error(ToolString.stack2string(e));
	// } finally {
	// t.finish();
	// }
	// }
	// LOG.debug(flickrSetId + " : " + nb + " photos");
	// }
	// });
	// }
}
