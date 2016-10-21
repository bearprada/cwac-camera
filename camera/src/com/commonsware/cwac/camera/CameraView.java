/***
  Copyright (c) 2013-2014 CommonsWare, LLC
  Portions Copyright (C) 2007 The Android Open Source Project
  
  Licensed under the Apache License, Version 2.0 (the "License"); you may
  not use this file except in compliance with the License. You may obtain
  a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package com.commonsware.cwac.camera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.CameraInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.commonsware.cwac.camera.CameraHost.FailureReason;

public class CameraView extends ViewGroup implements AutoFocusCallback {
  static final String TAG="CWAC-Camera";
  private PreviewStrategy previewStrategy;
  private Camera.Size previewSize;
  private Camera camera=null;
  private boolean inPreview=false;
  private CameraHost host=null;
  private OnOrientationChange onOrientationChange=null;
  private int displayOrientation=-1;
  private int outputOrientation=-1;
  private int cameraId=-1;
  private MediaRecorder recorder=null;
  private Camera.Parameters previewParams=null;
  private boolean isDetectingFaces=false;
  private boolean isAutoFocusing=false;
  private int lastPictureOrientation=-1;
    private Camera.PreviewCallback callback;

    public CameraView(Context context) {
    super(context);

    onOrientationChange=new OnOrientationChange(context.getApplicationContext());
  }

  public CameraView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public CameraView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    onOrientationChange=new OnOrientationChange(context.getApplicationContext());

    if (context instanceof CameraHostProvider) {
      setHost(((CameraHostProvider)context).getCameraHost());
    }
    else {
      throw new IllegalArgumentException("To use the two- or "
          + "three-parameter constructors on CameraView, "
          + "your activity needs to implement the "
          + "CameraHostProvider interface");
    }
  }

  public CameraHost getHost() {
    return(host);
  }

  // must call this after constructor, before onResume()

  public void setHost(CameraHost host) {
    this.host=host;
      host.getDeviceProfile();
//    if (host.getDeviceProfile().useTextureView()) {
//      previewStrategy=new TexturePreviewStrategy(this);
//    }
//    else {
      previewStrategy=new SurfacePreviewStrategy(this);
//    }
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  public void onResume() {
    View view = previewStrategy.getWidget();
    if (indexOfChild(view) == -1) {
        addView(view);
    }

    if (camera == null) {
      cameraId=getHost().getCameraId();

      if (cameraId >= 0) {
        try {
          camera=Camera.open(cameraId);

          if (getActivity().getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            onOrientationChange.enable();
          }

          setCameraDisplayOrientation();
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
              && getHost() instanceof Camera.FaceDetectionListener) {
            camera.setFaceDetectionListener((Camera.FaceDetectionListener)getHost());
          }
        }
        catch (Exception e) {
          getHost().onCameraFail(FailureReason.UNKNOWN);
        }
      }
      else {
        getHost().onCameraFail(FailureReason.NO_CAMERAS_REPORTED);
      }
    }
  }

  public void onPause() {
    if (camera != null) {
      previewDestroyed();
    }

    removeView(previewStrategy.getWidget());
    onOrientationChange.disable();
    lastPictureOrientation=-1;
  }

  // based on CameraPreview.java from ApiDemos

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int width=
        resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
    final int height=
        resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
    setMeasuredDimension(width, height);
    
    if (width > 0 && height > 0) {
      if (camera != null) {
        Camera.Size newSize=null;

        try {
          if (getHost().getRecordingHint() != CameraHost.RecordingHint.STILL_ONLY) {
            // Camera.Size deviceHint=
            // host.getDeviceProfile()
            // .getPreferredPreviewSizeForVideo(getDisplayOrientation(),
            // width,
            // height,
            // camera.getParameters());

            newSize=
                getHost().getPreferredPreviewSizeForVideo(getDisplayOrientation(),
                                                          width,
                                                          height,
                                                          camera.getParameters(),
                                                          null);

            // if (newSize != null) {
            // android.util.Log.wtf("CameraView",
            // String.format("getPreferredPreviewSizeForVideo: %d x %d",
            // newSize.width,
            // newSize.height));
            // }
          }

          if (newSize == null || newSize.width * newSize.height < 65536) {
            newSize=
                getHost().getPreviewSize(getDisplayOrientation(),
                                         width, height,
                                         camera.getParameters());
          }
        }
        catch (Exception e) {
          android.util.Log.e(getClass().getSimpleName(),
                             "Could not work with camera parameters?",
                             e);
          // TODO get this out to library clients
        }

        if (newSize != null) {
          if (previewSize == null) {
            previewSize=newSize;
          }
          else if (previewSize.width != newSize.width
              || previewSize.height != newSize.height) {
            if (inPreview) {
              stopPreview();
            }

            previewSize=newSize;
            initPreview(width, height);
          }
        }
      }
    }
  }

  // based on CameraPreview.java from ApiDemos

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    if (/*changed &&*/ getChildCount() > 0) {
      final View child=getChildAt(0);
      final int width=r - l;
      final int height=b - t;
      int previewWidth=width;
      int previewHeight=height;

      // handle orientation

      if (previewSize != null) {
        if (getDisplayOrientation() == 90
            || getDisplayOrientation() == 270) {
          previewWidth=previewSize.height;
          previewHeight=previewSize.width;
        }
        else {
          previewWidth=previewSize.width;
          previewHeight=previewSize.height;
        }
      }

      boolean useFirstStrategy=
          (width * previewHeight > height * previewWidth);
//      boolean useFullBleed=getHost().useFullBleedPreview();
//      if ((useFirstStrategy && !useFullBleed)
//          || (!useFirstStrategy && useFullBleed)) {
      // we add a condition for check previewHeight because this issue
      // https://fabric.io/cardinalblue/android/apps/com.cardinalblue.android.cami/issues/55f23ee3f5d3a7f76befbcd3
      if (!useFirstStrategy && previewHeight != 0) {
        final int scaledChildWidth=
            previewWidth * height / previewHeight;
        child.layout((width - scaledChildWidth) / 2, 0,
                     (width + scaledChildWidth) / 2, height);
      }
      else {
        final int scaledChildHeight=
            previewHeight * width / previewWidth;
        child.layout(0, (height - scaledChildHeight) / 2, width,
                     (height + scaledChildHeight) / 2);
      }
    }
  }

  public int getDisplayOrientation() {
    return(displayOrientation);
  }

  public void lockToLandscape(boolean enable) {
    if (enable) {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
      onOrientationChange.enable();
    }
    else {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      onOrientationChange.disable();
    }
  }

  public void restartPreview() {
    if (!inPreview) {
      startPreview();
    }
  }

  public void takePicture(boolean needBitmap, boolean needByteArray) {
    PictureTransaction xact=new PictureTransaction(getHost());

    takePicture(xact.needBitmap(needBitmap)
                    .needByteArray(needByteArray));
  }

  public void takePicture(final PictureTransaction xact) {
    if (inPreview) {
      if (isAutoFocusing) {
        throw new IllegalStateException("Camera cannot take a picture while auto-focusing");
      } else {
        previewParams=camera.getParameters();

        Camera.Parameters pictureParams = camera.getParameters();

        Camera.Size pictureSize = xact.host.getPictureSize(xact, pictureParams);

        if (pictureSize == null) {
          throw new IllegalArgumentException("can not get any valid picture size from camera " + cameraId);
        }
        pictureParams.setPictureSize(pictureSize.width,
                  pictureSize.height);
        pictureParams.setPictureFormat(ImageFormat.JPEG);

        if (xact.flashMode != null) {
          pictureParams.setFlashMode(xact.flashMode);
        }

//        if (!onOrientationChange.isEnabled()) {
          setCameraPictureOrientation(pictureParams);
//        }

        camera.setParameters(xact.host.adjustPictureParameters(xact,
                pictureParams));
        xact.cameraView=this;

//        postDelayed(new Runnable() {
//          @Override
//          public void run() {
            try {
              camera.takePicture(xact, null,
                                 new PictureTransactionCallback(xact));
            }
            catch (Exception e) {
              android.util.Log.e(getClass().getSimpleName(),
                                 "Exception taking a picture", e);
              // TODO get this out to library clients
            }
//          }
//        }, xact.host.getDeviceProfile().getPictureDelay());

        inPreview=false;
      }
    }
    else {
      throw new IllegalStateException(
                                      "Preview mode must have started before you can take a picture");
    }
  }

  public boolean isRecording() {
    return(recorder != null);
  }

  public void record() throws Exception {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
      throw new UnsupportedOperationException(
                                              "Video recording supported only on API Level 11+");
    }

//    if (displayOrientation != 0 && displayOrientation != 180) {
//      throw new UnsupportedOperationException(
//                                              "Video recording supported only in landscape");
//    }

    Camera.Parameters pictureParams=camera.getParameters();
    setCameraPictureOrientation(pictureParams);
    Camera.Size videoSize = CameraUtils.getBestResolutionVideoSize(previewSize.width, previewSize.height, pictureParams);
    camera.setParameters(pictureParams);

//    stopPreview();
    camera.unlock();

    try {
      recorder=new MediaRecorder();
      recorder.setCamera(camera);
      getHost().configureRecorderAudio(cameraId, recorder);
      recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
      getHost().configureRecorderProfile(cameraId, recorder, videoSize);
      getHost().configureRecorderOutput(cameraId, recorder);
      recorder.setOrientationHint(outputOrientation);
//      previewStrategy.attach(recorder);
      recorder.prepare();
      recorder.start();
    }
    catch (IOException e) {
      recorder.release();
      recorder=null;
      throw e;
    }
  }

  public void stopRecording() throws IOException {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
      throw new UnsupportedOperationException(
                                              "Video recording supported only on API Level 11+");
    }

    MediaRecorder tempRecorder=recorder;

    recorder=null;
    tempRecorder.stop();
    tempRecorder.release();
    camera.reconnect();
    startPreview();
  }

  public void autoFocus() {
    if (inPreview) {
      camera.autoFocus(this);
      isAutoFocusing=true;
    }
  }

  public void cancelAutoFocus() {
    camera.cancelAutoFocus();
  }

  public boolean isAutoFocusAvailable() {
    return(inPreview);
  }

  @Override
  public void onAutoFocus(boolean success, Camera camera) {
    isAutoFocusing=false;

    if (getHost() instanceof AutoFocusCallback) {
      getHost().onAutoFocus(success, camera);
    }
  }

  public String getFlashMode() {
    return(camera.getParameters().getFlashMode());
  }

  public void setFlashMode(String mode) {
    if (camera != null) {
      Camera.Parameters params=camera.getParameters();

      params.setFlashMode(mode);
      camera.setParameters(params);
    }
  }

  public ZoomTransaction zoomTo(int level) {
    if (camera == null) {
      throw new IllegalStateException(
                                      "Yes, we have no camera, we have no camera today");
    }
    else {
      Camera.Parameters params=camera.getParameters();

      if (level >= 0 && level <= params.getMaxZoom()) {
        return(new ZoomTransaction(camera, level));
      }
      else {
        throw new IllegalArgumentException(
                                           String.format("Invalid zoom level: %d",
                                                         level));
      }
    }
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  public void startFaceDetection() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
        && camera != null && !isDetectingFaces
        && camera.getParameters().getMaxNumDetectedFaces() > 0) {
      camera.startFaceDetection();
      isDetectingFaces=true;
    }
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  public void stopFaceDetection() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
        && camera != null && isDetectingFaces) {
      try {
        camera.stopFaceDetection();
      }
      catch (Exception e) {
        // TODO get this out to hosting app
      }

      isDetectingFaces=false;
    }
  }

  public boolean doesZoomReallyWork() {
    Camera.CameraInfo info=new Camera.CameraInfo();
    Camera.getCameraInfo(getHost().getCameraId(), info);

    return(getHost().getDeviceProfile().doesZoomActuallyWork(info.facing == CameraInfo.CAMERA_FACING_FRONT));
  }

  void previewCreated() {
    if (camera != null) {
      try {
        previewStrategy.attach(camera);
      }
      catch (IOException e) {
        getHost().handleException(e);
      }
    }
  }

  void previewDestroyed() {
    if (camera != null) {
      previewStopped();
      camera.release();
      camera=null;
    }
  }

  void previewReset(int width, int height) {
    previewStopped();
    initPreview(width, height);
  }

  private void previewStopped() {
    if (inPreview) {
      stopPreview();
    }
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  public void initPreview(int w, int h) {
    if (camera != null) {
      Camera.Parameters parameters;
      try {
        parameters = camera.getParameters();
      } catch (RuntimeException e) {
        return;
      }
      stopPreview();
      parameters.setPreviewSize(previewSize.width, previewSize.height);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
        parameters.setRecordingHint(getHost().getRecordingHint() != CameraHost.RecordingHint.STILL_ONLY);
      }

      requestLayout();
      camera.setParameters(getHost().adjustPreviewParameters(parameters));
      startPreview();
    }
  }

  private void startPreview() {
    if (inPreview || camera == null) {
        return;
    }
    if (callback != null) {
        camera.setPreviewCallback(callback);
    }
    try {
      camera.startPreview();
      inPreview = true;
      getHost().autoFocusAvailable();
    } catch (RuntimeException e) {
      getHost().onCameraFail(FailureReason.UNKNOWN);
    }
  }

  private void stopPreview() {
    if (!inPreview) {
        return;
    }
    inPreview=false;
    camera.setPreviewCallback(null);
    getHost().autoFocusUnavailable();
    camera.stopPreview();
  }

  // based on
  // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
  // and http://stackoverflow.com/a/10383164/115145

  private void setCameraDisplayOrientation() {
    Camera.CameraInfo info=new Camera.CameraInfo();
    int rotation=
        getActivity().getWindowManager().getDefaultDisplay()
                     .getRotation();
    int degrees=0;
    DisplayMetrics dm=new DisplayMetrics();

    Camera.getCameraInfo(cameraId, info);
    getActivity().getWindowManager().getDefaultDisplay().getMetrics(dm);

    switch (rotation) {
      case Surface.ROTATION_0:
        degrees=0;
        break;
      case Surface.ROTATION_90:
        degrees=90;
        break;
      case Surface.ROTATION_180:
        degrees=180;
        break;
      case Surface.ROTATION_270:
        degrees=270;
        break;
    }

    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      displayOrientation=(info.orientation + degrees) % 360;
      displayOrientation=(360 - displayOrientation) % 360;
    }
    else {
      displayOrientation=(info.orientation - degrees + 360) % 360;
    }

    boolean wasInPreview=inPreview;

    if (inPreview) {
      stopPreview();
    }

    camera.setDisplayOrientation(displayOrientation);

    if (wasInPreview) {
      startPreview();
    }
  }

  private void setCameraPictureOrientation(Camera.Parameters params) {
    Camera.CameraInfo info=new Camera.CameraInfo();

    Camera.getCameraInfo(cameraId, info);

    if (getActivity().getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
      outputOrientation=
          getCameraPictureRotation(getActivity().getWindowManager()
                                                .getDefaultDisplay()
                                                .getOrientation());
    }
    else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      outputOrientation=(360 - displayOrientation) % 360;
    }
    else {
      outputOrientation=displayOrientation;
    }

    if (lastPictureOrientation != outputOrientation) {
      params.setRotation(outputOrientation);
      lastPictureOrientation=outputOrientation;
    }
  }

  // based on:
  // http://developer.android.com/reference/android/hardware/Camera.Parameters.html#setRotation(int)

  private int getCameraPictureRotation(int orientation) {
    Camera.CameraInfo info=new Camera.CameraInfo();
    Camera.getCameraInfo(cameraId, info);
    int rotation=0;

    orientation=(orientation + 45) / 90 * 90;

    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      rotation=(info.orientation - orientation + 360) % 360;
    }
    else { // back-facing camera
      rotation=(info.orientation + orientation) % 360;
    }

    return(rotation);
  }

  Activity getActivity() {
    return((Activity)getContext());
  }

    public void setPreviewCallback(Camera.PreviewCallback callback) {
        this.callback = callback;
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public void setCameraFocus(MotionEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH && camera != null) {
            Camera.Parameters param = camera.getParameters();

            List<String> modes = param.getSupportedFocusModes();
            if (!modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                return;
            }
            camera.cancelAutoFocus();
            Rect focusRect = calculateTapArea(event.getX(), event.getY(), 1f);

            param.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            ArrayList<Camera.Area> l = new ArrayList<Camera.Area>();
            l.add(new Camera.Area(focusRect, 1000));
            param.setFocusAreas(l);
            if (param.getMaxNumMeteringAreas() > 0) {
                Rect meteringRect = calculateTapArea(event.getX(), event.getY(), 1.5f);
                l = new ArrayList<Camera.Area>();
                l.add(new Camera.Area(meteringRect, 1000));
                param.setMeteringAreas(l);
            }
            camera.setParameters(param);
            camera.autoFocus(this);
        }
    }


    /**
     * Convert touch position x:y to {@link Camera.Area} position -1000:-1000 to 1000:1000.
     */
    private Rect calculateTapArea(float x, float y, float coefficient) {
        int focusAreaSize = getContext().getResources().getDimensionPixelSize(R.dimen.camera_focus_size);
        int areaSize = Float.valueOf(focusAreaSize * coefficient).intValue();

        int sw = previewStrategy.getWidget().getWidth();
        int sh = previewStrategy.getWidget().getHeight();
        int left = clamp((int) x - areaSize / 2, 0, sw - areaSize);
        int top = clamp((int) y - areaSize / 2, 0, sh - areaSize);

        RectF rectF = new RectF(left, top, left + areaSize, top + areaSize);
        Matrix matrix = new Matrix();
        matrix.setScale(1, 1);
        matrix.postScale(2000f / sw, 2000f / sh);
        matrix.postTranslate(-1000f, -1000f);
        matrix.mapRect(rectF);
        return new Rect(Math.round(rectF.left), Math.round(rectF.top), Math.round(rectF.right), Math.round(rectF.bottom));
    }

    private int clamp(int x, int min, int max) {
        if (x > max) {
            return max;
        }
        if (x < min) {
            return min;
        }
        return x;
    }

    private class OnOrientationChange extends OrientationEventListener {
    private boolean isEnabled=false;

    public OnOrientationChange(Context context) {
      super(context);
      disable();
    }

    @Override
    public void onOrientationChanged(int orientation) {
//      if (camera != null && orientation != ORIENTATION_UNKNOWN) {
//        int newOutputOrientation=getCameraPictureRotation(orientation);
//
//        if (newOutputOrientation != outputOrientation) {
//          outputOrientation=newOutputOrientation;
//
//          Camera.Parameters params=camera.getParameters();
//
//          params.setRotation(outputOrientation);
//
//          try {
//            camera.setParameters(params);
//            lastPictureOrientation=outputOrientation;
//          }
//          catch (Exception e) {
//            Log.e(getClass().getSimpleName(),
//                  "Exception updating camera parameters in orientation change",
//                  e);
//            // TODO: get this info out to hosting app
//          }
//        }
//      }
    }

    @Override
    public void enable() {
      isEnabled=true;
      super.enable();
    }

    @Override
    public void disable() {
      isEnabled=false;
      super.disable();
    }

    boolean isEnabled() {
      return(isEnabled);
    }
  }

  private class PictureTransactionCallback implements
      Camera.PictureCallback {
    PictureTransaction xact=null;

    PictureTransactionCallback(PictureTransaction xact) {
      this.xact=xact;
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {
      camera.setParameters(previewParams);

      if (data != null) {
        new ImageCleanupTask(getContext(), data, cameraId, xact).start();
      }

      if (!xact.useSingleShotMode()) {
        startPreview();
      }
    }
  }
}