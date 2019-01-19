/*
 * Copyright 2019 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.client;

import android.support.annotation.Nullable;
import android.util.Base64;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.util.TextUrl;
import com.hippo.yorozuya.FileUtils;
import com.hippo.yorozuya.IOUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

public class EhTagDatabase {

  private final String name;
  private final byte[] tags;

  public EhTagDatabase(String name, BufferedSource source) throws IOException {
    this.name = name;
    int totalBytes = source.readInt();
    tags = new byte[totalBytes];
    source.readFully(tags);
  }

  public String getTranslation(String tag) {
    return search(tags, tag.getBytes(TextUrl.UTF_8));
  }

  @Nullable
  private String search(byte[] tags, byte[] tag) {
    int low = 0;
    int high = tags.length;
    while (low < high) {
      int start = (low + high) / 2;
      // Look for the starting '\n'
      while (start > -1 && tags[start] != '\n') {
        start--;
      }
      start++;

      // Look for the middle '\r'.
      int middle = 1;
      while (tags[start + middle] != '\r') {
        middle++;
      }

      // Look for the ending '\n'
      int end = middle + 1;
      while (tags[start + end] != '\n') {
        end++;
      }

      int compare;
      int tagIndex = 0;
      int curIndex = start;

      for (;;) {
        int tagByte = tag[tagIndex] & 0xff;
        int curByte = tags[curIndex] & 0xff;
        compare = tagByte - curByte;
        if (compare != 0) {
          break;
        }

        tagIndex++;
        curIndex++;
        if (tagIndex == tag.length && curIndex == start + middle) {
          break;
        }
        if (tagIndex == tag.length) {
          compare = -1;
          break;
        }
        if (curIndex == start + middle) {
          compare = 1;
          break;
        }
      }

      if (compare < 0) {
        high = start - 1;
      } else if (compare > 0) {
        low = start + end + 1;
      } else {
        byte[] bytes = Base64.decode(tags, start + middle + 1, end - middle - 1, Base64.DEFAULT);
        return new String(bytes, TextUrl.UTF_8);
      }
    }
    return null;
  }


  private static final String[] METADATA_ZH_CN = new String[] {
      "tag-translations-zh-rCN.sha1",
      "https://github.com/seven332/EhViewer/raw/tag-translations/tag-translations/tag-translations-zh-rCN.sha1",
      "tag-translations-zh-rCN",
      "https://github.com/seven332/EhViewer/raw/tag-translations/tag-translations/tag-translations-zh-rCN",
  };

  private static final Map<String, String> NAMESPACE_TO_PREFIX = new HashMap<>();

  static {
    NAMESPACE_TO_PREFIX.put("artist", "a:");
    NAMESPACE_TO_PREFIX.put("character", "c:");
    NAMESPACE_TO_PREFIX.put("female", "f:");
    NAMESPACE_TO_PREFIX.put("group", "g:");
    NAMESPACE_TO_PREFIX.put("language", "l:");
    NAMESPACE_TO_PREFIX.put("male", "m:");
    NAMESPACE_TO_PREFIX.put("misc", "");
    NAMESPACE_TO_PREFIX.put("parody", "p:");
    NAMESPACE_TO_PREFIX.put("reclass", "r:");
  }

  private static volatile EhTagDatabase instance;
  // TODO more lock for different language
  private static Lock lock = new ReentrantLock();

  @Nullable
  public static EhTagDatabase getInstance() {
    return instance;
  }

  @Nullable
  public static String namespaceToPrefix(String namespace) {
    return NAMESPACE_TO_PREFIX.get(namespace);
  }

  private static String[] getMetadata() {
    Locale locale = Locale.getDefault();
    if ("zh".equals(locale.getLanguage()) && "CN".equals(locale.getCountry())) {
      return METADATA_ZH_CN;
    }
    return null;
  }

  public static boolean isPossible() {
    return getMetadata() != null;
  }

  @Nullable
  private static byte[] getFileContent(File file, int length) {
    try (BufferedSource source = Okio.buffer(Okio.source(file))) {
      byte[] content = new byte[length];
      source.readFully(content);
      return content;
    } catch (IOException e) {
      return null;
    }
  }

  @Nullable
  private static byte[] getFileSha1(File file) {
    try (InputStream is = new FileInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      int n;
      byte[] buffer = new byte[4 * 1024];
      while ((n = is.read(buffer)) != -1) {
        digest.update(buffer, 0, n);
      }
      return digest.digest();
    } catch (IOException e) {
      return null;
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }

  private static boolean equals(byte[] b1, byte[] b2) {
    if (b1 == null && b2 == null) {
      return true;
    }
    if (b1 == null || b2 == null) {
      return false;
    }

    if (b1.length != b2.length) {
      return false;
    }

    for (int i = 0; i < b1.length; i++) {
      if (b1[i] != b2[i]) {
        return false;
      }
    }

    return true;
  }

  private static boolean checkData(File sha1File, File dataFile) {
    byte[] s1 = getFileContent(sha1File, 20);
    if (s1 == null) {
      return false;
    }

    byte[] s2 = getFileSha1(dataFile);
    if (s2 == null) {
      return false;
    }

    return equals(s1, s2);
  }

  private static boolean save(OkHttpClient client, String url, File file) {
    Request request = new Request.Builder().url(url).build();
    Call call = client.newCall(request);
    try (Response response = call.execute()) {
      if (!response.isSuccessful()) {
        return false;
      }
      ResponseBody body = response.body();
      if (body == null) {
        return false;
      }

      try (InputStream is = body.byteStream(); OutputStream os = new FileOutputStream(file)) {
        IOUtils.copy(is, os);
      }

      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public static void update() {
    String[] urls = getMetadata();
    if (urls == null || urls.length != 4) {
      // Clear tags if it's not possible
      instance = null;
      return;
    }

    String sha1Name = urls[0];
    String sha1Url = urls[1];
    String dataName = urls[2];
    String dataUrl = urls[3];

    // Clear tags if name if different
    EhTagDatabase tmp = instance;
    if (tmp != null && !tmp.name.equals(dataName)) {
      instance = null;
    }

    IoThreadPoolExecutor.getInstance().execute(() -> {
      if (!lock.tryLock()) {
        return;
      }

      try {
        File dir = AppConfig.getFilesDir("tag-translations");
        if (dir == null) {
          return;
        }

        // Check current sha1 and current data
        File sha1File = new File(dir, sha1Name);
        File dataFile = new File(dir, dataName);
        if (!checkData(sha1File, dataFile)) {
          FileUtils.delete(sha1File);
          FileUtils.delete(dataFile);
        }

        // Read current EhTagDatabase
        if (instance == null && dataFile.exists()) {
          try (BufferedSource source = Okio.buffer(Okio.source(dataFile))) {
            instance = new EhTagDatabase(dataName, source);
          } catch (IOException e) {
            FileUtils.delete(sha1File);
            FileUtils.delete(dataFile);
          }
        }

        OkHttpClient client = EhApplication.getOkHttpClient(EhApplication.getInstance());

        // Save new sha1
        File tempSha1File = new File(dir, sha1Name + ".tmp");
        if (!save(client, sha1Url, tempSha1File)) {
          FileUtils.delete(tempSha1File);
          return;
        }

        // Check new sha1 and current data
        if (checkData(tempSha1File, dataFile)) {
          // The data is the same
          FileUtils.delete(tempSha1File);
          return;
        }

        // Save new data
        File tempDataFile = new File(dir, dataName + ".tmp");
        if (!save(client, dataUrl, tempDataFile)) {
          FileUtils.delete(tempDataFile);
          return;
        }

        // Check new sha1 and new data
        if (!checkData(tempSha1File, tempDataFile)) {
          FileUtils.delete(tempSha1File);
          FileUtils.delete(tempDataFile);
          return;
        }

        // Replace current sha1 and current data with new sha1 and new data
        FileUtils.delete(sha1File);
        FileUtils.delete(dataFile);
        tempSha1File.renameTo(sha1File);
        tempDataFile.renameTo(dataFile);

        // Read new EhTagDatabase
        try (BufferedSource source = Okio.buffer(Okio.source(dataFile))) {
          instance = new EhTagDatabase(dataName, source);
        } catch (IOException e) {
          // Ignore
        }
      } finally {
        lock.unlock();
      }
    });
  }
}
