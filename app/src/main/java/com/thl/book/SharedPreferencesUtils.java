package com.thl.book;

import android.content.Context;
import android.content.SharedPreferences;


public class SharedPreferencesUtils {

	public static final String SP_NAME = "config";

	public static void saveBoolean(Context context, String key, boolean value) {
		context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.edit().putBoolean(key, value).apply();
	}

	public static boolean getBoolean(Context context, String key, boolean defValue) {
		return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.getBoolean(key, defValue);
	}

	public static void saveString(Context context, String key, String value) {
		context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.edit().putString(key, value).apply();
	}

	public static String getString(Context context, String key, String defValue) {
		return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.getString(key, defValue);
	}

	public static void saveLong(Context context, String key, long value) {
		context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.edit().putLong(key, value).apply();
	}

	public static long getLong(Context context, String key, long defValue) {
		return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.getLong(key, defValue);
	}

	public static void saveInt(Context context, String key, int value) {
		context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.edit().putInt(key, value).apply();
	}

	public static int getInt(Context context, String key, int defValue) {
		return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.getInt(key, defValue);
	}

	/**
	 * Synchronous write — use only when the value must be persisted before the
	 * process could be killed (e.g. one-time initialisation flags).
	 */
	public static void saveBooleanSync(Context context, String key, boolean value) {
		context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
				.edit().putBoolean(key, value).commit();
	}

	public static void clearPreferences(Context context) {
		context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit().clear().apply();
	}
}
