package jp.ac.hosei.cis.k.matsumoto.lowmediamovie;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.ActionBar.LayoutParams;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
	private class LowMediaMoviePlayer {
		private Thread inputThread;
		private boolean inputThreadIsContinue = true;
		private Thread outputThread;
		private boolean outputThreadIsContinue = true;
		private MediaCodec codec;
		private Bitmap bitmap;

		public Bitmap getBitmap() {
			return bitmap;
		}

		public void colorConvert(int[] rgb, byte[] yuv, int width, int height) {
			final int frameSize = width * height;

			for (int j = 0, yp = 0; j < height; j++) {
				int up = frameSize + (j >> 1) * (width / 2);
				int vp = frameSize + (frameSize / 4) + (j >> 1) * (width / 2);
				int u = 0, v = 0;
				for (int i = 0; i < width; i++, yp++) {
					int y = (0xff & ((int) yuv[yp])) - 16;
					if (y < 0)
						y = 0;
					if ((i & 1) == 0) {

						v = (0xff & yuv[vp++]) - 128;
						u = (0xff & yuv[up++]) - 128;
					}

					int y1192 = 1192 * y;
					int r = (y1192 + 1634 * v);
					int g = (y1192 - 833 * v - 400 * u);
					int b = (y1192 + 2066 * u);

					if (r < 0)
						r = 0;
					else if (r > 262143)
						r = 262143;
					if (g < 0)
						g = 0;
					else if (g > 262143)
						g = 262143;
					if (b < 0)
						b = 0;
					else if (b > 262143)
						b = 262143;

					rgb[yp] = 0xff000000 | ((r << 6) & 0xff0000)
							| ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
				}
			}
		}

		public void play(final MediaExtractor extractor, final String mime,
				final MediaFormat format) {

			inputThreadIsContinue = true;
			outputThreadIsContinue = true;

			// TODO:本来は利用できるコーデックを調査してからコレを実行するべき。
			codec = MediaCodec.createByCodecName("OMX.google.h264.decoder");
			codec.configure(format, null, null, 0);
			codec.start();

			inputThread = new Thread(new Runnable() {

				@Override
				public void run() {

					boolean sawInputEOS = false;

					ByteBuffer[] inputBuffers = codec.getInputBuffers();

					// 高めのプライオリティを設定
					Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);

					while (inputThreadIsContinue) {
						// InputBufferを取り出す
						final int inputBufIndex = codec
								.dequeueInputBuffer(1000 * 1000);

						// InputBufferを取り出せたか
						if (inputBufIndex >= 0) {
							// 今回使うByteBufferを取得
							final ByteBuffer dstBuf = inputBuffers[inputBufIndex];

							// Extractorからサンプルデータ（圧縮済み)を取得
							int sampleSize = extractor
									.readSampleData(dstBuf, 0);

							long presentationTimeUs = 0;
							if (sampleSize < 0) {
								// サンプルデータ（圧縮済み）が取れなかったらEOSと判定
								sawInputEOS = true;
								sampleSize = 0;
								break;
							} else {
								// Extractorからサンプルデータが表す時間を取得
								presentationTimeUs = extractor.getSampleTime();
							}

							// 取得し終わったInputBufferをCodecへ返却
							codec.queueInputBuffer(
									inputBufIndex,
									0, // offset
									sampleSize,
									presentationTimeUs,
									extractor.getSampleFlags() == MediaExtractor.SAMPLE_FLAG_SYNC ? MediaCodec.BUFFER_FLAG_SYNC_FRAME
											: sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM
													: 0);

							// EOSに到達していなかったら、次のサンプルデータ（圧縮済み）に移動する
							if (!sawInputEOS) {
								extractor.advance();
							}

						} else {
							try {
								Thread.sleep(16);
							} catch (InterruptedException e) {
								throw new RuntimeException(e);
							}

						}
					}

				}
			});

			outputThread = new Thread(new Runnable() {

				@Override
				public void run() {
					ByteBuffer[] outputBuffers = null;
					MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
					byte[] framebuffer = null;
					int[] argb8888framebuffer = null;
					int width = 0, height = 0;

					// 高めのプライオリティを設定
					Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);

					while (outputThreadIsContinue) {

						// デコード結果が格納されたBufferのIndexを取得
						final int res = codec.dequeueOutputBuffer(bufferInfo,
								10);
						if (res >= 0) {
							int outputBufIndex = res;
							ByteBuffer buf = outputBuffers[outputBufIndex];

							if (framebuffer == null
									|| framebuffer.length != bufferInfo.size) {
								framebuffer = new byte[bufferInfo.size];
							}
							if (argb8888framebuffer == null
									|| argb8888framebuffer.length < width
											* height) {
								argb8888framebuffer = new int[width * height];
								bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
							}

							buf.position(bufferInfo.offset);
							buf.get(framebuffer);
							buf.clear();

							colorConvert(argb8888framebuffer, framebuffer,
									width, height);


							bitmap.setPixels(argb8888framebuffer, 0, width, 0, 0, width, height);

							codec.releaseOutputBuffer(outputBufIndex, false);

							if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
								break;
							}
						} else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
							outputBuffers = codec.getOutputBuffers();
						} else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
							width = format.getInteger(MediaFormat.KEY_WIDTH);
							height = format.getInteger(MediaFormat.KEY_HEIGHT);

						}
					}

				}
			});

			inputThread.start();
			outputThread.start();
		}

		private void stop() {
			inputThreadIsContinue = false;
			outputThreadIsContinue = false;
			try {
				inputThread.join();
				outputThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			inputThread = null;
			outputThread = null;
			codec.stop();
			codec.release();
		}
	}

	private LowMediaMoviePlayer lowMediaMoviePlayer = new LowMediaMoviePlayer();

	private String analyzeAndPlay(final String path) {
		StringBuffer ret = new StringBuffer();

		MediaExtractor extractor = new MediaExtractor();
		extractor.setDataSource(path);
		ret.append("path : " + path + "\n");

		final int trackCount = extractor.getTrackCount();
		ret.append("track count : " + trackCount + "\n");

		List<MediaFormat> trackMediaFormatList = new ArrayList<MediaFormat>();
		for (int i = 0; i < trackCount; i++) {
			final MediaFormat format = extractor.getTrackFormat(i);
			final String mime = format.getString(MediaFormat.KEY_MIME);
			trackMediaFormatList.add(format);

			ret.append("MIME : " + mime + "\n");
			final long duration = format.getLong(MediaFormat.KEY_DURATION);
			ret.append("\tDuration : " + duration + "\n");

			if (mime.startsWith("video/")) {
				final int height = format.getInteger(MediaFormat.KEY_HEIGHT);
				final int width = format.getInteger(MediaFormat.KEY_WIDTH);

				ret.append("\tHeight : " + height + "\n");
				ret.append("\tWidth : " + width + "\n");
				extractor.selectTrack(i);
				lowMediaMoviePlayer.play(extractor, mime, format);

				// １トラックのみの再生に対応
				break;

			} else if (mime.startsWith("audio/")) {
				final int channelCount = format
						.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
				final int sampleRate = format
						.getInteger(MediaFormat.KEY_SAMPLE_RATE);

				ret.append("\tChannel Count : " + channelCount + "\n");
				ret.append("\tSample Rate : " + sampleRate + "\n");

			} else {
			}

		}
		return ret.toString();
	}


	class BitmapSurfaceView extends SurfaceView implements
			SurfaceHolder.Callback {

		public BitmapSurfaceView(Context context) {
			super(context);
			getHolder().addCallback(this);
		}

		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width,
				int height) {
		}

		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			// TODO:本当はこの書き方は問題
			new Thread(new Runnable() {

				@Override
				public void run() {
					for (;;) {
						Canvas canvas = getHolder().lockCanvas();
						canvas.drawColor(0, Mode.CLEAR);
						if (lowMediaMoviePlayer.getBitmap() != null)
							canvas.drawBitmap(lowMediaMoviePlayer.getBitmap(),
									0, 0, new Paint());
						getHolder().unlockCanvasAndPost(canvas);

						try {
							Thread.sleep(16);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}

				}
			}).start();

		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {

		}

	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		((LinearLayout) findViewById(R.id.movieContainer)).addView(
				new BitmapSurfaceView(this), new LinearLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.WRAP_CONTENT));

		((Button) findViewById(R.id.analyzeAndPlayButton))
				.setOnClickListener(new OnClickListener() {

					@Override
					public void onClick(View v) {
						final String mediaPath = ((EditText) findViewById(R.id.mediaPathEditText))
								.getText().toString();

						((EditText) findViewById(R.id.resultEditText))
								.setText(analyzeAndPlay(mediaPath));
					}
				});

		((Button) findViewById(R.id.stopButton))
				.setOnClickListener(new OnClickListener() {

					@Override
					public void onClick(View v) {
						lowMediaMoviePlayer.stop();
					}
				});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

}
