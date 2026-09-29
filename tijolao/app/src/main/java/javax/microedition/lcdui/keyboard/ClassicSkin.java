package javax.microedition.lcdui.keyboard;

import static javax.microedition.lcdui.keyboard.ClassicKeypad.BOXES;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.CALL;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.END;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.FIRE;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.SECTORS;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.SOFT_LEFT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.SOFT_RIGHT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.UP;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import java.util.Objects;

import javax.microedition.lcdui.keyboard.ClassicKeypad.Box;

import ru.playsoftware.j2meloader.R;

/**
 * Paints the classic keypad: a graphite phone body with keys in relief, a round pad, and the
 * green and red keys. A pressed key sinks and its label lights up, like a keypad backlight.
 */
final class ClassicSkin {
	private static final int BODY_TOP = 0xFF30333A;
	private static final int BODY_BOTTOM = 0xFF1B1D21;
	private static final int BODY_EDGE_LIGHT = 0xFF4B5058;
	private static final int BODY_EDGE_DARK = 0xFF0E0F11;
	private static final int SHADOW = 0xFF0F1013;
	private static final int KEY_TOP = 0xFF4E535B;
	private static final int KEY_BOTTOM = 0xFF3A3E45;
	private static final int KEY_PRESSED = 0xFF2A2D32;
	private static final int CALL_TOP = 0xFF45A052;
	private static final int CALL_BOTTOM = 0xFF327A3C;
	private static final int CALL_PRESSED = 0xFF285F30;
	private static final int END_TOP = 0xFFCC4B43;
	private static final int END_BOTTOM = 0xFFA13A34;
	private static final int END_PRESSED = 0xFF7E2D28;
	private static final int PAD_TOP = 0xFF5B6169;
	private static final int PAD_BOTTOM = 0xFF3B3F46;
	private static final int OK_TOP = 0xFF6A7079;
	private static final int OK_BOTTOM = 0xFF4A4F57;
	private static final int RIM = 0x30FFFFFF;
	private static final int LABEL = 0xFFF1F3F4;
	private static final int LETTERS = 0xFFA7ADB5;
	private static final int LIT = 0xFF9CD3FF;
	private static final int PAD_LIT = 0x609CD3FF;

	private static final String[] DIGITS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
	private static final String[] LETTERS_OF = {
			".,?", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "+", "␣", "⇧"
	};
	/** Cap height of the digits, as a share of the text size (Roboto). */
	private static final float CAP = 0.71f;

	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
	private final Paint letters = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
	private final Drawable callIcon;
	private final Drawable endIcon;

	ClassicSkin(Context context) {
		stroke.setStyle(Paint.Style.STROKE);
		digits.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
		letters.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		callIcon = Objects.requireNonNull(ContextCompat.getDrawable(context, R.drawable.ic_vk_call)).mutate();
		endIcon = Objects.requireNonNull(ContextCompat.getDrawable(context, R.drawable.ic_vk_call_end)).mutate();
	}

	/**
	 * The shapes of one layout, made once when the screen changes size (on any thread) and then
	 * only read by {@link #paint}.
	 */
	static final class Frame {
		final ClassicKeypad keypad;
		final Path[] keys = new Path[BOXES];
		final Shader[] faces = new Shader[BOXES];
		final Shader[] body;
		final Path[] wedges = new Path[SECTORS.length];
		final Path[] arrows = new Path[4];
		final Shader pad;
		final Shader ok;
		/** How far the keys stand out of the body. */
		final float depth;

		Frame(ClassicKeypad keypad) {
			this.keypad = keypad;
			depth = Math.max(2, 0.05f * keypad.unit);
			body = new Shader[keypad.body.length];
			for (int i = 0; i < body.length; i++) {
				Box b = keypad.body[i];
				body[i] = new LinearGradient(0, b.top, 0, b.bottom, BODY_TOP, BODY_BOTTOM, Shader.TileMode.CLAMP);
			}
			Path hole = new Path();
			hole.addCircle(keypad.padX, keypad.padY, keypad.padR + keypad.ringGap, Path.Direction.CW);
			for (int part = 0; part < BOXES; part++) {
				Box b = keypad.boxes[part];
				float corner = Math.min(b.width(), b.height()) * (keypad.aroundPad(part) ? 0.3f : 0.34f);
				Path key = new Path();
				key.addRoundRect(b.left, b.top, b.right, b.bottom, corner, corner, Path.Direction.CW);
				if (keypad.aroundPad(part)) {
					key.op(hole, Path.Op.DIFFERENCE);
				}
				keys[part] = key;
				int top = part == CALL ? CALL_TOP : part == END ? END_TOP : KEY_TOP;
				int bottom = part == CALL ? CALL_BOTTOM : part == END ? END_BOTTOM : KEY_BOTTOM;
				faces[part] = new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP);
			}

			float x = keypad.padX;
			float y = keypad.padY;
			float r = keypad.padR;
			pad = new LinearGradient(0, y - r, 0, y + r, PAD_TOP, PAD_BOTTOM, Shader.TileMode.CLAMP);
			ok = new LinearGradient(0, y - keypad.okR, 0, y + keypad.okR, OK_TOP, OK_BOTTOM, Shader.TileMode.CLAMP);
			Path center = new Path();
			center.addCircle(x, y, keypad.okR * 1.1f, Path.Direction.CW);
			RectF bounds = new RectF(x - r, y - r, x + r, y + r);
			for (int i = 0; i < SECTORS.length; i++) {
				Path wedge = new Path();
				wedge.moveTo(x, y);
				// Sectors count from straight up; Android arcs from the right.
				wedge.arcTo(bounds, SECTORS[i][0] - 90, SECTORS[i][1]);
				wedge.close();
				wedge.op(center, Path.Op.DIFFERENCE);
				wedges[i] = wedge;
			}
			float at = (keypad.okR + r) / 2;
			float size = (r - keypad.okR) * 0.3f;
			for (int i = 0; i < 4; i++) {
				double angle = Math.toRadians(i * 90);
				float dx = (float) Math.sin(angle);
				float dy = (float) -Math.cos(angle);
				Path arrow = new Path();
				arrow.moveTo(x + dx * (at + size * 0.55f), y + dy * (at + size * 0.55f));
				float baseX = x + dx * (at - size * 0.45f);
				float baseY = y + dy * (at - size * 0.45f);
				arrow.lineTo(baseX - dy * size * 0.6f, baseY + dx * size * 0.6f);
				arrow.lineTo(baseX + dy * size * 0.6f, baseY - dx * size * 0.6f);
				arrow.close();
				arrows[i] = arrow;
			}
		}
	}

	/** Main thread only. {@code pressed} is indexed by the parts of {@link ClassicKeypad}. */
	void paint(Canvas canvas, Frame frame, boolean[] pressed) {
		ClassicKeypad keypad = frame.keypad;
		for (int i = 0; i < keypad.body.length; i++) {
			Box b = keypad.body[i];
			fill.setShader(frame.body[i]);
			canvas.drawRect(b.left, b.top, b.right, b.bottom, fill);
		}
		fill.setShader(null);
		paintEdges(canvas, keypad);
		for (int part = 0; part < BOXES; part++) {
			paintKey(canvas, frame, part, pressed[part]);
		}
		paintPad(canvas, frame, pressed);
	}

	/** A bevel where the body meets the game, as around the screen of a phone. */
	private void paintEdges(Canvas canvas, ClassicKeypad keypad) {
		float width = Math.max(1, keypad.unit * 0.012f);
		stroke.setStrokeWidth(width);
		if (keypad.landscape) {
			Box left = keypad.body[0];
			Box right = keypad.body[1];
			stroke.setColor(BODY_EDGE_DARK);
			canvas.drawLine(left.right - width / 2, left.top, left.right - width / 2, left.bottom, stroke);
			canvas.drawLine(right.left + width / 2, right.top, right.left + width / 2, right.bottom, stroke);
			stroke.setColor(BODY_EDGE_LIGHT);
			canvas.drawLine(left.right - width * 1.5f, left.top, left.right - width * 1.5f, left.bottom, stroke);
			canvas.drawLine(right.left + width * 1.5f, right.top, right.left + width * 1.5f, right.bottom, stroke);
		} else {
			Box body = keypad.body[0];
			stroke.setColor(BODY_EDGE_DARK);
			canvas.drawLine(body.left, body.top + width / 2, body.right, body.top + width / 2, stroke);
			stroke.setColor(BODY_EDGE_LIGHT);
			canvas.drawLine(body.left, body.top + width * 1.5f, body.right, body.top + width * 1.5f, stroke);
		}
	}

	private void paintKey(Canvas canvas, Frame frame, int part, boolean down) {
		Path key = frame.keys[part];
		int save = canvas.save();
		if (down) {
			canvas.translate(0, frame.depth * 0.7f);
			fill.setColor(part == CALL ? CALL_PRESSED : part == END ? END_PRESSED : KEY_PRESSED);
			canvas.drawPath(key, fill);
		} else {
			canvas.translate(0, frame.depth);
			fill.setColor(SHADOW);
			canvas.drawPath(key, fill);
			canvas.translate(0, -frame.depth);
			fill.setShader(frame.faces[part]);
			canvas.drawPath(key, fill);
			fill.setShader(null);
			stroke.setColor(RIM);
			stroke.setStrokeWidth(Math.max(1, frame.keypad.unit * 0.01f));
			canvas.drawPath(key, stroke);
		}
		ClassicKeypad keypad = frame.keypad;
		Box box = keypad.boxes[part];
		float x = keypad.labelX(part);
		float y = box.centerY();
		float room = 2 * Math.min(x - box.left, box.right - x);
		if (part < SOFT_LEFT) {
			paintNumber(canvas, part, box, down);
		} else if (part <= SOFT_RIGHT) {
			// Soft keys were blank, with a dash: the game shows what they do on its screen.
			float w = Math.min(room * 0.32f, keypad.unit * 0.55f);
			float h = Math.max(2, keypad.unit * 0.07f);
			fill.setColor(down ? LIT : LETTERS);
			canvas.drawRoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, h / 2, h / 2, fill);
		} else {
			Drawable icon = part == CALL ? callIcon : endIcon;
			int size = (int) Math.min(box.height() * 0.56f, room * 0.45f);
			icon.setBounds((int) (x - size / 2f), (int) (y - size / 2f),
					(int) (x + size / 2f), (int) (y + size / 2f));
			icon.setTint(down ? LIT : LABEL);
			icon.draw(canvas);
		}
		canvas.restoreToCount(save);
	}

	/** The digit with its letters: side by side on wide keys, one over the other on tall ones. */
	private void paintNumber(Canvas canvas, int part, Box box, boolean down) {
		String digit = DIGITS[part];
		String text = LETTERS_OF[part];
		digits.setColor(down ? LIT : LABEL);
		letters.setColor(down ? LIT : LETTERS);
		float w = box.width();
		float h = box.height();
		if (w >= 1.6f * h) {
			digits.setTextSize(h * 0.52f);
			letters.setTextSize(h * 0.27f);
			float digitWidth = digits.measureText(digit);
			float gap = h * 0.14f;
			float lettersWidth = letters.measureText(text);
			float left = box.centerX() - (digitWidth + gap + lettersWidth) / 2;
			float baseline = box.centerY() + digits.getTextSize() * CAP / 2;
			digits.setTextAlign(Paint.Align.LEFT);
			letters.setTextAlign(Paint.Align.LEFT);
			canvas.drawText(digit, left, baseline, digits);
			canvas.drawText(text, left + digitWidth + gap, baseline, letters);
		} else {
			digits.setTextSize(Math.min(h * 0.42f, w * 0.55f));
			letters.setTextSize(Math.min(h * 0.2f, w * 0.24f));
			float fit = w * 0.85f / Math.max(1, letters.measureText(text));
			if (fit < 1) {
				letters.setTextSize(letters.getTextSize() * fit);
			}
			digits.setTextAlign(Paint.Align.CENTER);
			letters.setTextAlign(Paint.Align.CENTER);
			float x = box.centerX();
			canvas.drawText(digit, x, box.centerY() - h * 0.04f, digits);
			canvas.drawText(text, x, box.centerY() + h * 0.3f, letters);
		}
	}

	private void paintPad(Canvas canvas, Frame frame, boolean[] pressed) {
		ClassicKeypad keypad = frame.keypad;
		float x = keypad.padX;
		float y = keypad.padY;
		float r = keypad.padR;
		fill.setColor(SHADOW);
		canvas.drawCircle(x, y + frame.depth, r, fill);
		fill.setShader(frame.pad);
		canvas.drawCircle(x, y, r, fill);
		fill.setShader(null);

		fill.setColor(PAD_LIT);
		for (int i = 0; i < SECTORS.length; i++) {
			if (pressed[UP + i]) {
				canvas.drawPath(frame.wedges[i], fill);
			}
		}
		float rim = Math.max(1, keypad.unit * 0.012f);
		stroke.setStrokeWidth(rim);
		stroke.setColor(RIM);
		canvas.drawCircle(x, y, r - rim / 2, stroke);

		for (int i = 0; i < 4; i++) {
			// An arrow lights up with its direction or with a diagonal next to it.
			int direction = UP + i * 2;
			int before = i == 0 ? UP + SECTORS.length - 1 : direction - 1;
			boolean lit = pressed[direction] || pressed[before] || pressed[direction + 1];
			fill.setColor(lit ? LIT : LABEL);
			canvas.drawPath(frame.arrows[i], fill);
		}

		// The groove around the center button, then the button.
		float groove = r * 0.06f;
		stroke.setStrokeWidth(groove);
		stroke.setColor(SHADOW);
		canvas.drawCircle(x, y, keypad.okR + groove / 2, stroke);
		boolean down = pressed[FIRE];
		if (down) {
			fill.setColor(KEY_PRESSED);
			canvas.drawCircle(x, y + frame.depth * 0.4f, keypad.okR, fill);
			stroke.setStrokeWidth(rim);
			stroke.setColor(LIT);
			canvas.drawCircle(x, y + frame.depth * 0.4f, keypad.okR - rim / 2, stroke);
		} else {
			fill.setShader(frame.ok);
			canvas.drawCircle(x, y, keypad.okR, fill);
			fill.setShader(null);
			stroke.setStrokeWidth(rim);
			stroke.setColor(RIM);
			canvas.drawCircle(x, y, keypad.okR - rim / 2, stroke);
		}
	}
}
