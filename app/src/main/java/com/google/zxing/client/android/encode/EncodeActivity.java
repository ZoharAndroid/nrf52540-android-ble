/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android.encode;

import android.app.Application;
import android.content.DialogInterface;
import android.graphics.Point;
import android.provider.MediaStore;
import android.view.Display;
import android.view.MenuInflater;
import android.view.WindowManager;
import com.google.zxing.WriterException;
import com.google.zxing.client.android.Contents;
import com.google.zxing.client.android.FinishListener;
import com.google.zxing.client.android.Intents;
import zohar.com.ndn_liteble.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * This class encodes data from an Intent into a QR code, and then displays it full screen so that
 * another person can scan it with their device.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class EncodeActivity extends Activity {

  private static final String TAG = EncodeActivity.class.getSimpleName();

  private static final int MAX_BARCODE_FILENAME_LENGTH = 24;
  private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");
  private static final String USE_VCARD_KEY = "USE_VCARD";

  private QRCodeEncoder qrCodeEncoder;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    Intent intent = getIntent();
    if (intent == null) {
      finish();
    } else {
      String action = intent.getAction();
      if (Intents.Encode.ACTION.equals(action) || Intent.ACTION_SEND.equals(action)) {
        setContentView(R.layout.encode);
      } else {
        finish();
      }
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater menuInflater = getMenuInflater();
    menuInflater.inflate(R.menu.encode, menu);
    boolean useVcard = qrCodeEncoder != null && qrCodeEncoder.isUseVCard();
    int encodeNameResource = useVcard ? R.string.menu_encode_mecard : R.string.menu_encode_vcard;
    MenuItem encodeItem = menu.findItem(R.id.menu_encode);
    encodeItem.setTitle(encodeNameResource);
    Intent intent = getIntent();
    if (intent != null) {
      String type = intent.getStringExtra(Intents.Encode.TYPE);
      encodeItem.setVisible(Contents.Type.CONTACT.equals(type));
    }
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.menu_share:
        share();
        return true;
      case R.id.menu_encode:
        Intent intent = getIntent();
        if (intent == null) {
          return false;
        }
        intent.putExtra(USE_VCARD_KEY, !qrCodeEncoder.isUseVCard());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
        return true;
      default:
        return false;
    }
  }
  
  private void share() {
    QRCodeEncoder encoder = qrCodeEncoder;
    if (encoder == null) { // Odd
      Log.w(TAG, "No existing barcode to send?");
      return;
    }

    String contents = encoder.getContents();
    if (contents == null) {
      Log.w(TAG, "No existing barcode to send?");
      return;
    }

    Bitmap bitmap;
    try {
      bitmap = encoder.encodeAsBitmap();
    } catch (WriterException we) {
      Log.w(TAG, we);
      return;
    }
    if (bitmap == null) {
      return;
    }

    File bsRoot = new File(Environment.getExternalStorageDirectory(), "BarcodeScanner");
    File barcodesRoot = new File(bsRoot, "Barcodes");
    if (!barcodesRoot.exists() && !barcodesRoot.mkdirs()) {
      Log.w(TAG, "Couldn't make dir " + barcodesRoot);
      showErrorMessage(R.string.msg_unmount_usb);
      return;
    }
    File barcodeFile = new File(barcodesRoot, makeBarcodeFileName(contents) + ".png");
    if (!barcodeFile.delete()) {
      Log.w(TAG, "Could not delete " + barcodeFile);
      // continue anyway
    }
    try (FileOutputStream fos = new FileOutputStream(barcodeFile)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 0, fos);
    } catch (IOException ioe) {
      Log.w(TAG, "Couldn't access file " + barcodeFile + " due to " + ioe);
      showErrorMessage(R.string.msg_unmount_usb);
      return;
    }

    Intent intent = new Intent(Intent.ACTION_SEND, Uri.parse("mailto:"));
    intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name) + " - " + encoder.getTitle());
    intent.putExtra(Intent.EXTRA_TEXT, contents);
    intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + barcodeFile.getAbsolutePath()));
    intent.setType("image/png");
    intent.addFlags(Intents.FLAG_NEW_DOC);
    startActivity(Intent.createChooser(intent, null));
  }

  private static CharSequence makeBarcodeFileName(CharSequence contents) {
    String fileName = NOT_ALPHANUMERIC.matcher(contents).replaceAll("_");
    if (fileName.length() > MAX_BARCODE_FILENAME_LENGTH) {
      fileName = fileName.substring(0, MAX_BARCODE_FILENAME_LENGTH);
    }
    return fileName;
  }

  @Override
  protected void onResume() {
    super.onResume();
    // This assumes the view is full screen, which is a good assumption
    WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
    Display display = manager.getDefaultDisplay();
    Point displaySize = new Point();
    display.getSize(displaySize);
    int width = displaySize.x;
    int height = displaySize.y;
    int smallerDimension = width < height ? width : height;
    smallerDimension = smallerDimension * 7 / 8;

    Intent intent = getIntent();
    if (intent == null) {
      return;
    }

    try {
      boolean useVCard = intent.getBooleanExtra(USE_VCARD_KEY, false);
      qrCodeEncoder = new QRCodeEncoder(this, intent, smallerDimension, useVCard);
      final Bitmap bitmap = qrCodeEncoder.encodeAsBitmap();
      if (bitmap == null) {
        Log.w(TAG, "Could not encode barcode");
        showErrorMessage(R.string.msg_encode_contents_failed);
        qrCodeEncoder = null;
        return;
      }

      ImageView view = (ImageView) findViewById(R.id.image_view);
      view.setImageBitmap(bitmap);

      TextView contents = (TextView) findViewById(R.id.contents_text_view);
      final String filename;
      if (intent.getBooleanExtra(Intents.Encode.SHOW_CONTENTS, true)) {
        filename = qrCodeEncoder.getDisplayContents();
        contents.setText(qrCodeEncoder.getDisplayContents());
        setTitle(qrCodeEncoder.getTitle());
      } else {
        filename = "";
        contents.setText("");
        setTitle("");
      }

      // 保存图片
      final AlertDialog.Builder builder = new AlertDialog.Builder(EncodeActivity.this);
      builder.setTitle("是否保存？");
      builder.setMessage("是否保存" + "\"" + filename + "\"" + "二维码图片？" );
      builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          saveBitmap(bitmap, filename);
        }
      });
      builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
        }
      });
      builder.show();

    } catch (WriterException e) {
      Log.w(TAG, "Could not encode barcode", e);
      showErrorMessage(R.string.msg_encode_contents_failed);
      qrCodeEncoder = null;
    }
  }

  private void showErrorMessage(int message) {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setMessage(message);
    builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
    builder.setOnCancelListener(new FinishListener(this));
    builder.show();
  }

  /**
   * 保存二维码图片
   *
   * @param bitmap
   * @param filename
   * @return
   */
  private  String saveBitmap(Bitmap bitmap, String filename) {
    try {
      // 获取内置SD卡路径
      String sdCardPath = Environment.getExternalStorageDirectory().getPath();
      // 图片文件路径
      File file = new File(sdCardPath);
      File[] files = file.listFiles();
      for (int i = 0; i < files.length; i++) {
        File file1 = files[i];
        String name = file1.getName();
        if (name.endsWith(filename + ".png")) {
          boolean flag = file1.delete();
         // LogUtils.print("删除 + " + flag);
        }
      }
      String filePath = sdCardPath + "/" + filename + ".png";
      file = new File(filePath);
      FileOutputStream os = new FileOutputStream(file);
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
      os.flush();
      os.close();

      //把文件插入到系统图库
      MediaStore.Images.Media.insertImage(getContentResolver(),
              file.getAbsolutePath(), filename + ".png", null);

      //保存图片后发送广播通知更新数据库
      Uri uri = Uri.fromFile(file);
      sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));

      Toast.makeText(this,filename + "二维码保存成功",Toast.LENGTH_SHORT).show();

      return filePath;
    } catch (IOException e) {
      e.printStackTrace();
      return "";
    }
  }
}
