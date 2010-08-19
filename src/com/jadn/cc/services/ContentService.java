package com.jadn.cc.services;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.jadn.cc.R;
import com.jadn.cc.core.Config;
import com.jadn.cc.core.Location;
import com.jadn.cc.core.PlaySet;
import com.jadn.cc.core.Subscription;
import com.jadn.cc.trace.ExceptionHandler;
import com.jadn.cc.trace.TraceUtil;
import com.jadn.cc.ui.CarCast;

public class ContentService extends Service implements OnCompletionListener  {

	//Jam jam;
	MediaPlayer mediaPlayer = new MediaPlayer();

	MetaHolder metaHolder;
	PlaySet currentSet = PlaySet.PODCASTS;
	
	// CarCast activity;
	int current;

	private int currentDuration() {
		if (current >= metaHolder.getSize()) {
			return 0;
		}
		int dur = cm().getDuration();
		if (dur != -1)
			return dur;
		if (mediaMode == MediaMode.UnInitialized) {
			// ask media player
			try {
				mediaPlayer.reset();
				mediaPlayer.setDataSource(currentFile().toString());
				mediaPlayer.prepare();
				cm().setDuration(mediaPlayer.getDuration());
			} catch (Exception e) {
				TraceUtil.report(new RuntimeException("on file " + currentFile().toString(), e));
				cm().setDuration(0);
			}
		} else {
			cm().setDuration(mediaPlayer.getDuration());
		}
		return cm().getDuration();
	}

	private MetaFile cm() {
		return metaHolder.get(current);
	}

	int currentPostion() {
		if (current >= metaHolder.getSize()) {
			return 0;
		}
		return metaHolder.get(current).getCurrentPos();
	}

	public String currentTitle() {
		if (current >= metaHolder.getSize()) {
			if (currentSet == PlaySet.PODCASTS) {
				if (downloadHelper != null) {
					return "\nDownloading podcasts\n" + downloadHelper.getStatus();
				}
				return "No podcasts loaded.\nUse 'Menu' and 'Download Podcasts'";
			}
			if (currentSet == PlaySet.NOTES_HOME)
				return "No Home Notes";
			return "No Work Notes";
		}
		return cm().getTitle();
	}

	public File currentFile() {
		return cm().file;
	}

	@Override
	public void onCompletion(MediaPlayer mp) {
		cm().setCurrentPos(-1);
		cm().save();
		if (PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("autoPlayNext", true)) {
			next(true);
		}
	}

	void next() {
		boolean wasPlaying = mediaPlayer.isPlaying();
		if (wasPlaying) {
			mediaPlayer.stop();
			cm().setCurrentPos(mediaPlayer.getCurrentPosition());
		} else {
		}
		next(wasPlaying);
	}

	void next(boolean playing) {
		mediaMode = MediaMode.UnInitialized;

		// if we are at end.
		if (current + 1 >= metaHolder.getSize()) {
			saveState();
			// activity.disableJumpButtons();
			mediaPlayer.reset();
			// say(activity, "That's all folks");
			if (location != null) {
				location.pos = 0;
			}
			return;
		}

		current++;
		if (playing)
			play();
	}

	private void play() {
		try {
			mediaPlayer.reset();
			if (current >= metaHolder.getSize())
				return;

			mediaPlayer.setDataSource(currentFile().toString());
			mediaPlayer.prepare();
			mediaPlayer.seekTo(metaHolder.get(current).getCurrentPos());
			// say(activity, "started " + currentTitle());
			mediaPlayer.start();
			mediaMode = MediaMode.Playing;
			saveState();
		} catch (Exception e) {
			TraceUtil.report(e);
		}
	}

	public void previous() {

		boolean playing = false;
		if (mediaPlayer.isPlaying()) {
			playing = true;
			mediaPlayer.stop();
			// say(activity, "stopped " + currentTitle());
		}
		mediaMode = MediaMode.UnInitialized;
		if (current > 0) {
			current--;
		}
		if (current >= metaHolder.getSize())
			return;
		if (playing)
			play();

		// final TextView textView = (TextView) activity
		// .findViewById(R.id.summary);
		// textView.setText(currentTitle());
	}

	enum MediaMode {
		UnInitialized, Playing, Paused
	};

	MediaMode mediaMode = MediaMode.UnInitialized;

	public MediaMode getMediaMode() {
		return mediaMode;
	}

	public void setMediaMode(MediaMode mediaMode) {
		this.mediaMode = mediaMode;
	}

	/** @returns playing or not */
	boolean pauseOrPlay() {
		try {
			if (mediaPlayer.isPlaying()) {
				pauseNow();
				return false;
			} else {
				if (mediaMode == MediaMode.Paused) {
					mediaPlayer.start();
					mediaMode = MediaMode.Playing;
					// activity.enableJumpButtons();
				} else {
					play();
				}
				return true;
			}
		} catch (Exception e) {
			return false;
		}
	}

	public void pauseNow() {
		if (mediaPlayer.isPlaying()) {
			mediaPlayer.pause();
			mediaMode = MediaMode.Paused;
			cm().setCurrentPos(mediaPlayer.getCurrentPosition());
			cm().save();
			// say(activity, "paused " + currentTitle());
			saveState();
		}
		// activity.disableJumpButtons();
	}

	public void bump(int bump) {
		if (current >= metaHolder.getSize())
			return;
		try {
			int npos = mediaPlayer.getCurrentPosition() + bump * 1000;
			if (npos < 0) {
				npos = 0;
			} else if (npos > mediaPlayer.getDuration()) {
				npos = mediaPlayer.getDuration() - 1;
				mediaPlayer.seekTo(npos);
			}
			mediaPlayer.seekTo(npos);
		} catch (Exception e) {
		}
		if (!mediaPlayer.isPlaying()) {
			saveState();
		}

	}

	// used by status when mediaplayer is not started.

	Location location;

	public void restoreState() {
		final File stateFile = new File(currentSet.getRoot(), "state.dat");
		if (!stateFile.exists()) {
			location = null;
			return;
		}
		try {
			if (location == null) {
				location = Location.load(stateFile);
			}
			tryToRestoreLocation();
		} catch (Throwable e) {
			// bummer.
		}

	}

	private void tryToRestoreLocation() {
		try {
			if (location == null) {
				return;
			}
			boolean found = false;
			for (int i = 0; i < metaHolder.getSize(); i++) {
				if (metaHolder.get(i).getTitle().equals(location.title)) {
					current = i;
					found = true;
					break;
				}
			}
			if (!found) {
				location = null;
				return;
			}
			mediaPlayer.reset();
			mediaPlayer.setDataSource(currentFile().toString());
			mediaPlayer.prepare();
			mediaPlayer.seekTo(location.pos);
			mediaMode = MediaMode.Paused;
		} catch (Throwable e) {
			// bummer.
		}

	}

	public void saveState() {
		try {
			final File stateFile = new File(currentSet.getRoot(), "state.dat");
			location = Location.save(stateFile, currentTitle(), mediaPlayer.getCurrentPosition(), mediaPlayer.getDuration());
		} catch (Throwable e) {
			// bummer.
		}
	}

	public Location getLocation() {
		if (mediaMode == MediaMode.UnInitialized) {
			return new Location(currentTitle(), currentPostion());
		}
		return new Location(currentTitle(), mediaPlayer.getCurrentPosition());
	}

	public String getLocationString() {
		Location useLocation = getLocation();
		return getTimeString(useLocation.pos);
	}

	public String getDurationString() {
		return getTimeString(currentDuration());
	}

	public static String getTimeString(int time) {
		StringBuilder sb = new StringBuilder();
		int min = time / (1000 * 60);
		if (min < 10)
			sb.append('0');
		sb.append(min);
		sb.append(':');
		int sec = (time - min * 60 * 1000) / 1000;
		if (sec < 10)
			sb.append('0');
		sb.append(sec);
		return sb.toString();
	}

	public CharSequence currentSummary() {
		StringBuilder sb = new StringBuilder();
		if (currentSet == PlaySet.NOTES_HOME)
			sb.append("--Home Notes--\n");
		if (currentSet == PlaySet.NOTES_WORK)
			sb.append("--Work Notes--\n");
		if (current >= metaHolder.getSize()) {
			if (currentSet == PlaySet.PODCASTS) {
				if (downloadHelper != null)
					return "\nDownloading podcasts";
				return "\nNo Podcasts have been downloaded.";
			}
			if (currentSet == PlaySet.NOTES_HOME)
				return "No Home Notes";
			return "No Work Notes";
		}
		sb.append(cm().getFeedName());
		sb.append('\n');
		sb.append(cm().getTitle());
		return sb.toString();
	}

	public String getWhereString() {
		StringBuilder sb = new StringBuilder();
		if (metaHolder.getSize() == 0)
			sb.append('0');
		else
			sb.append(current + 1);
		sb.append('/');
		sb.append(metaHolder.getSize());
		return sb.toString();
	}

	public int currentProgress() {
		if (mediaMode == MediaMode.UnInitialized) {
			int duration = currentDuration();
			if (duration == 0)
				return 0;
			return currentPostion() * 100 / duration;
		}
		return mediaPlayer.getCurrentPosition() * 100 / mediaPlayer.getDuration();
	}

	public void purgeHeard() {
		deleteUpTo(current);
	}

	public void eraseHistory() {
		DownloadHistory.eraseHistory();
	}

	public void moveTo(double d) {
		if (mediaMode == MediaMode.UnInitialized) {
			if (currentDuration() == 0)
				return;
			metaHolder.get(current).setCurrentPos((int) (d * currentDuration()));
			mediaPlayer.reset();
			try {
				mediaPlayer.setDataSource(currentFile().toString());
				mediaPlayer.prepare();
			} catch (Exception e) {
				TraceUtil.report(e);
			}
			mediaMode = MediaMode.Paused;
			return;
		}
		mediaPlayer.seekTo((int) (d * mediaPlayer.getDuration()));
	}

	void deleteUpTo(int upTo) {
		if (mediaPlayer.isPlaying()) {
			pauseNow();
			mediaPlayer.stop();
			mediaPlayer.reset();
		}
		mediaMode = MediaMode.UnInitialized;
		if (upTo == -1)
			upTo = metaHolder.getSize();
		for (int i = 0; i < upTo; i++) {
			metaHolder.delete(0);
		}
		metaHolder = new MetaHolder();
		tryToRestoreLocation();
		if(location==null)
			current = 0;
	}

	public int getCount() {
		return metaHolder.getSize();
	}

	public void deleteSite(int position) {
		List<Subscription> sites = getSites();
		sites.remove(position);
		saveSubscriptions(sites);
	}

	static File siteListFile = new File(Config.CarCastRoot, "podcasts.txt");

	public List<Subscription> getSites() {
		if (!siteListFile.exists()) {
			siteListFile.getParentFile().mkdirs();
			resetToDemoSubscriptions();
		}
		if (!siteListFile.exists()) {
			return null;
		}
		List<Subscription> sites = new ArrayList<Subscription>();
		try {
			DataInputStream dis = new DataInputStream(new FileInputStream(siteListFile));
			String line = null;
			while ((line = dis.readLine()) != null) {
				try {
					Subscription site = Subscription.fromString(line);
					if (site != null)
						sites.add(site);
				} catch (Throwable t) {
					TraceUtil.report(new RuntimeException("line is " + line, t));
				}
			}
			return sites;
		} catch (Exception e1) {
			TraceUtil.report(e1);
			return new ArrayList<Subscription>();
		}
	}

	boolean wasPausedByPhoneCall;

	@Override
	public void onCreate() {
		super.onCreate();
		ExceptionHandler.register(this);

		PhoneStateListener phoneStateListener = new PhoneStateListener() {
			@Override
			public void onCallStateChanged(int state, String incomingNumber) {
				super.onCallStateChanged(state, incomingNumber);

				if (state == TelephonyManager.CALL_STATE_OFFHOOK || state == TelephonyManager.CALL_STATE_RINGING) {
					if (mediaPlayer.isPlaying()) {
						pauseNow();
						wasPausedByPhoneCall = true;
					}
				}

				if (state == TelephonyManager.CALL_STATE_IDLE && wasPausedByPhoneCall) {
					wasPausedByPhoneCall = false;
					pauseOrPlay();
				}
			}
		};

		final TelephonyManager telMgr = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
		telMgr.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

		Config.CarCastRoot.mkdirs();
		for (PlaySet playset : PlaySet.values()) {
			playset.getRoot().mkdir();
		}
		metaHolder = new MetaHolder();
		mediaPlayer.setOnCompletionListener(this);

		// restore state;
		current = 0;

		restoreState();
	}

	private final IContentService.Stub binder = new ContentServiceStub(this);

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	protected void saveSubscriptions(String[] sites) {
		try {
			DataOutputStream dos = new DataOutputStream(new FileOutputStream(siteListFile));
			for (String site : sites) {
				dos.writeBytes(site);
				dos.writeByte('\n');
			}
			dos.close();
		} catch (IOException e) {
			// esay(e);
		}

	}

	public static void saveSubscriptions(List<Subscription> sites) {
		try {
			DataOutputStream dos = new DataOutputStream(new FileOutputStream(siteListFile));
			for (Subscription site : sites) {
				dos.writeBytes(site.toString());
				dos.writeByte('\n');
			}
			dos.close();
		} catch (IOException e) {
			TraceUtil.report(e);
		}
	}

	protected String[] getSitesAsString() {
		List<Subscription> sites = getSites();
		if (sites == null)
			return null;
		String[] ss = new String[sites.size()];
		for (int i = 0; i < ss.length; i++) {
			ss[i] = sites.get(i).toString();
		}
		return ss;
	}

	DownloadHelper downloadHelper;

	void updateNotification(String update) {
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Activity.NOTIFICATION_SERVICE);

		Notification notification = new Notification(R.drawable.iconbusy, "Downloading started", System.currentTimeMillis());

		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, CarCast.class), 0);

		notification.setLatestEventInfo(getBaseContext(), "Downloading Started", update, contentIntent);
		notification.flags = Notification.FLAG_ONGOING_EVENT;

		mNotificationManager.notify(23, notification);

	}

	void startDownloadingNewPodCasts(final int max) {

		if (downloadHelper == null) {

			NotificationManager mNotificationManager = (NotificationManager) getSystemService(Activity.NOTIFICATION_SERVICE);
			mNotificationManager.cancel(22);

			updateNotification("Downloading podcasts started");

			new Thread() {
				@Override
				public void run() {
					downloadHelper = new DownloadHelper(max);
					downloadHelper.downloadNewPodCasts(ContentService.this, PreferenceManager.getDefaultSharedPreferences(
							getApplicationContext()).getString("accounts", "none"));
				}
			}.start();
		}
	}

	void doDownloadCompletedNotification(int got) {
		// Get the notification manager service.
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Activity.NOTIFICATION_SERVICE);

		mNotificationManager.cancel(23);

		// Allow UI to update download text (only when in debug mode) this seems
		// suboptimal
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		Notification notification = new Notification(R.drawable.icon2, "Download complete", System.currentTimeMillis());

		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, CarCast.class), 0);

		notification.setLatestEventInfo(getBaseContext(), "Downloads Finished", "Downloaded " + got + " podcasts.", contentIntent);
		notification.flags = Notification.FLAG_AUTO_CANCEL;

		mNotificationManager.notify(22, notification);

		// clear so next user request will start new download
		downloadHelper = null;

		metaHolder = new MetaHolder();
		if (current >= metaHolder.getSize()) {
			current = 0;
		}
	}

	public void deleteCurrentPodcast() {
		if (mediaMode == MediaMode.Playing) {
			pauseNow();
		}
		metaHolder.get(current).delete();
		metaHolder = new MetaHolder();
		if (current >= metaHolder.getSize()) {
			current = 0;
		}
	}

	public void deleteAllSubscriptions() {
		saveSubscriptions(new ArrayList<Subscription>());
	}

	public void resetToDemoSubscriptions() {
		List<Subscription> s = new ArrayList<Subscription>();
		add(s, "Science Channel=http://www.discovery.com/radio/xml/sciencechannel.xml");
		add(s, "Quirks and Quarks=http://www.cbc.ca/podcasting/includes/quirks.xml");
		add(s, "Cringely=http://www.cringely.com/feed/podcast/");
		add(s, "60 second science=http://rss.sciam.com/sciam/60secsciencepodcast");
		add(s, "60 second psych=http://rss.sciam.com/sciam/60-second-psych");
		add(s, "60 second earth=http://rss.sciam.com/sciam/60-second-earth");
		add(s, "New York Times Tech Talk=http://nytimes.com/services/xml/rss/nyt/podcasts/techtalk.xml");
		saveSubscriptions(s);
	}

	private void add(List<Subscription> s, String string) {
		Subscription sub = null;
		try {
			sub = Subscription.fromString(string);
			s.add(sub);
		} catch (MalformedURLException url) {
			// Do I just drop these, or do I mention it?
		}
	}

	public void deletePodcast(int position) {
		if (mediaPlayer.isPlaying() && current == position) {
			pauseNow();
		}
		metaHolder.delete(position);
		if (current >= metaHolder.getSize()) {
			if (current > 0)
				current--;
		}
		// If we are playing something after what's deleted, adjust the current
		if (current > position)
			current--;
		
	}

	public void play(int position) {
		if (mediaPlayer.isPlaying()) {
			mediaPlayer.stop();
		}
		current = position;
		play();
	}

	public void setCurrentPaused(int position) {
		boolean wasPlaying = mediaPlayer.isPlaying();
		if (wasPlaying) {
			cm().setCurrentPos(mediaPlayer.getCurrentPosition());
			mediaPlayer.stop();
		}
		mediaMode = MediaMode.Paused;
		current = position;
	}

	public String getCurrentSubscriptionName() {
		if (current >= metaHolder.getSize()) {
			return "";
		}
		return cm().getFeedName();
	}

	SearchHelper searchHelper;

	public String startSearch(String search) {
		if (search.equals("-status-")) {
			if (searchHelper.done)
				return "done";
			return "";
		}
		if (search.equals("-results-")) {
			return searchHelper.results;
		}

		searchHelper = new SearchHelper(search);
		searchHelper.start();
		return "";
	}

	public String getPodcastEmailSummary() {
		StringBuilder sb = new StringBuilder();
		if (current < metaHolder.getSize()) {
			MetaFile mf = cm();
			if (mf != null) {
				sb.append("\nWanted to let you know about this podcast:\n\n");
				sb.append("\nTitle: " + mf.getTitle());
				sb.append("\nFeed Title: " + mf.getFeedName());
				for (Subscription site : getSites()) {
					if (site.name.equals(mf.getFeedName())) {
						sb.append("\nFeed URL: " + site.url);
						break;
					}
				}
				if (mf.getUrl() != null) {
					sb.append("\nPodcast URL: " + mf.getUrl());
				}
			}
		}
		sb.append("\n\n\n");
		sb.append("This email sent from Car Cast.");
		return sb.toString();
	}

	public String encodedDownloadStatus() {
		if (downloadHelper == null) {
			return "";
		}
		String status = downloadHelper.sitesScanned + "," + downloadHelper.totalSites + "," + downloadHelper.podcastsDownloaded + ","
				+ downloadHelper.totalPodcasts + "," + downloadHelper.podcastsCurrentBytes + "," + downloadHelper.podcastsTotalBytes + ","
				+ downloadHelper.currentSubscription + "," + downloadHelper.currentTitle;
		// Log.w("CarCast",status);
		return status;
	}

	public boolean addSubscription(String subscription) {
		List<Subscription> slist = getSites();
		try {
			Subscription s = Subscription.fromString(subscription);
			for (Subscription sub : slist) {
				if (sub.url.equals(s.url))
					return false;
			}
			slist.add(s);
			saveSubscriptions(slist);
			return true;
		} catch (MalformedURLException e) {
			Log.e("CarCast", "addSubscription", e);
		}
		return false;
	}

}
