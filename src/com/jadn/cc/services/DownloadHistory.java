package com.jadn.cc.services;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import com.jadn.cc.core.PlaySet;
import com.jadn.cc.core.Sayer;

import android.util.Log;

public class DownloadHistory implements Sayer {

	private static File histFile = new File(PlaySet.PODCASTS.getRoot(),
			"history.prop");
	private List<String> history;

	private static DownloadHistory instance = null;

	/**
	 * Get the history object
	 * 
	 * @return the history
	 */
	public static DownloadHistory getInstance() {
		if (instance == null) {
			instance = new DownloadHistory();
		}
		return instance;
	}

	/**
	 * Create a object that represents the download history. It is backed to a
	 * file.
	 */
	private DownloadHistory() {
		history = new ArrayList<String>();
		try {
			DataInputStream dis = new DataInputStream(new FileInputStream(
					histFile));
			String line = null;
			while ((line = dis.readLine()) != null) {
				history.add(line);
			}
		} catch (Exception e) {
			Log.e(DownloadHelper.class.getName(), e.toString());
		}
	}

	/**
	 * Add a item to the history
	 * 
	 * @param shortName
	 *            the filename to be added
	 */
	public void add(String shortName) {
		history.add(shortName);
		try {
			PrintWriter histOut = new PrintWriter(
					new FileWriter(histFile, true));
			histOut.println(shortName);
			histOut.close();
		} catch (IOException e) {
			say("problem writing history file: " + histFile + " ex:" + e);
		}
	}

	/**
	 * Remove the backing storage
	 */
	public static void eraseHistory() {
		histFile.delete();
	}

	/**
	 * Get the current size of the download history
	 * 
	 * @return the size
	 */
	public int size() {
		return history.size();
	}

	/**
	 * Check if a item is in the history
	 * 
	 * @param urlShortName
	 *            the item to check for
	 * @return true it the item is in the history
	 */
	public boolean contains(String urlShortName) {
		return history.contains(urlShortName);
	}

	StringBuilder sb = new StringBuilder();

	@Override
	public void say(String text) {
		sb.append(text);
		sb.append('\n');
	}
}
