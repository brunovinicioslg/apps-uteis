package javax.microedition.lcdui.keyboard;

/**
 * Where the keys of the classic keypad go: the keypad of a phone of the time, beside or below the
 * game instead of over it. Standing, it takes the bottom of the screen: soft keys under the
 * corners of the game, a round five-way pad between the green and red keys, then the 3×4 number
 * keys. Lying down, the number keys go to the left of the game and the pad to the right.
 * <p>
 * Plain geometry, without Android, so that it can be tested on its own. Immutable: it is built
 * when the screen changes size (on any thread) and read when painting and touching.
 */
final class ClassicKeypad {

	// Parts of the keypad: the number keys (in reading order), the keys around the pad, the pad.
	static final int NONE = -1;
	static final int STAR = 9;
	static final int NUM_0 = 10;
	static final int POUND = 11;
	static final int SOFT_LEFT = 12;
	static final int SOFT_RIGHT = 13;
	static final int CALL = 14;
	static final int END = 15;
	/** Parts up to here are boxes; the rest are pieces of the round pad. */
	static final int BOXES = 16;
	static final int FIRE = 16;
	static final int UP = 17;
	static final int UP_RIGHT = 18;
	static final int RIGHT = 19;
	static final int DOWN_RIGHT = 20;
	static final int DOWN = 21;
	static final int DOWN_LEFT = 22;
	static final int LEFT = 23;
	static final int UP_LEFT = 24;
	static final int PARTS = 25;

	/**
	 * Where each direction of the pad starts, in degrees clockwise from straight up, and how wide
	 * it is. The arrows get 60°, the diagonals between them 30°: a thumb a little off an arrow
	 * still gets the arrow, as on the pads of the time, and the diagonals are there for the games
	 * that take two directions at once.
	 */
	static final float[][] SECTORS = {
			{-30, 60}, // UP
			{30, 30},  // UP_RIGHT
			{60, 60},  // RIGHT
			{120, 30}, // DOWN_RIGHT
			{150, 60}, // DOWN
			{210, 30}, // DOWN_LEFT
			{240, 60}, // LEFT
			{300, 30}, // UP_LEFT
	};

	// Standing: sizes in units of a sixth of the screen width, from the top of the keypad down.
	private static final float TOP = 0.15f;
	private static final float PAD = 1.85f;
	private static final float PAD_GAP = 0.22f;
	private static final float KEY = 0.64f;
	private static final float KEY_GAP = 0.1f;
	private static final float BOTTOM = 0.22f; // clear of the swipe that goes home
	/** The whole keypad at full size. */
	static final float DESIGN = TOP + PAD + PAD_GAP + 4 * KEY + 3 * KEY_GAP + BOTTOM;
	/** The keypad squeezed as far as it goes, on short screens or for tall games. */
	private static final float SQUEEZED = 4.3f;
	private static final float SIDE = 0.22f;
	private static final float KEY_GAP_X = 0.14f;
	private static final float SOFT = 0.62f;
	private static final float CALL_HEIGHT = 0.7f;

	// Lying down: sizes in units of a sixth of the screen height.
	private static final float PANEL_MIN = 1.9f;
	private static final float PANEL_MAX = 2.6f;

	/** The center button, as a share of the pad. */
	static final float OK_RATIO = 0.4f;

	final boolean landscape;
	/** Size unit (a sixth of the short side), for strokes and text. */
	final float unit;
	/** Boxes of the parts below {@link #BOXES}. */
	final Box[] boxes = new Box[BOXES];
	/** The phone's body: one area below the game, or one on each side of it. */
	final Box[] body;
	final float padX;
	final float padY;
	final float padR;
	final float okR;
	/** The gap between the pad and the keys shaped around it. */
	final float ringGap;
	/** How far a touch may land outside a key and still press it (half the gap between keys). */
	final float slop;

	/**
	 * The part of the screen left to the game, into which it is then scaled. Standing, the game
	 * gets the full width when it fits with a keypad that is not too squeezed; lying down, it gets
	 * the full height with a panel of keys on each side.
	 *
	 * @param gameWidth  the game's screen width, or 0 when not set
	 * @param gameHeight the game's screen height, or 0 when not set
	 */
	static Box gameArea(float width, float height, int gameWidth, int gameHeight) {
		boolean landscape = width > height;
		float aspect = gameWidth > 0 && gameHeight > 0 ? (float) gameWidth / gameHeight
				: landscape ? 4f / 3 : 3f / 4;
		if (!landscape) {
			float keypad = clamp(height - width / aspect,
					minKeypadHeight(width, height), maxKeypadHeight(width, height));
			return new Box(0, 0, width, height - keypad);
		}
		float unit = height / 6;
		float max = Math.min(PANEL_MAX * unit, 0.3f * width);
		float min = Math.min(PANEL_MIN * unit, max);
		float panel = clamp((width - height * aspect) / 2, min, max);
		return new Box(panel, 0, width - panel, height);
	}

	/**
	 * Lays the keys out around the game.
	 *
	 * @param game where the game ended up (scaled into {@link #gameArea})
	 */
	static ClassicKeypad layout(float width, float height, Box game) {
		return new ClassicKeypad(width, height, game);
	}

	private ClassicKeypad(float width, float height, Box game) {
		landscape = width > height;
		if (landscape) {
			float v = height / 6;
			unit = v;
			float panelMax = Math.min(PANEL_MAX * v, 0.3f * width);
			float panelMin = Math.min(PANEL_MIN * v, panelMax);
			float leftEdge = clamp(game.left, panelMin, width / 2);
			float rightEdge = clamp(game.right, width / 2, width - panelMin);
			body = new Box[]{new Box(0, 0, leftEdge, height), new Box(rightEdge, 0, width, height)};

			// The keys keep to the outer edges, within thumb reach, even beside a small game.
			float outer = 0.2f * v;
			float inner = 0.14f * v;
			float lx0 = outer;
			float lx1 = Math.min(leftEdge, panelMax) - inner;
			float rx0 = width - Math.min(width - rightEdge, panelMax) + inner;
			float rx1 = width - outer;
			float top = 0.22f * v;
			float bottom = height - 0.28f * v;
			float gapX = 0.1f * v;
			float gapY = 0.12f * v;

			boxes[SOFT_LEFT] = new Box(lx0, top, lx1, top + 0.6f * v);
			boxes[SOFT_RIGHT] = new Box(rx0, top, rx1, top + 0.6f * v);

			float keysTop = boxes[SOFT_LEFT].bottom + 0.3f * v;
			float keyH = Math.min(0.95f * v, (bottom - keysTop - 3 * gapY) / 4);
			float y0 = keysTop + (bottom - keysTop - (4 * keyH + 3 * gapY)) / 2;
			float keyW = (lx1 - lx0 - 2 * gapX) / 3;
			for (int i = 0; i < 12; i++) {
				float x = lx0 + (i % 3) * (keyW + gapX);
				float y = y0 + (i / 3) * (keyH + gapY);
				boxes[i] = new Box(x, y, x + keyW, y + keyH);
			}

			float middle = (rx0 + rx1) / 2;
			boxes[CALL] = new Box(rx0, bottom - 0.7f * v, middle - gapX / 2, bottom);
			boxes[END] = new Box(middle + gapX / 2, bottom - 0.7f * v, rx1, bottom);

			float padTop = boxes[SOFT_RIGHT].bottom + 0.25f * v;
			float padBottom = boxes[CALL].top - 0.25f * v;
			padR = Math.min(Math.min(rx1 - rx0, padBottom - padTop), 2.3f * v) / 2;
			padX = middle;
			padY = (padTop + padBottom) / 2;
			ringGap = 0.1f * v;
			slop = Math.min(gapX, gapY) / 2;
		} else {
			float u = width / 6;
			unit = u;
			float top = clamp(game.bottom, 0, height - minKeypadHeight(width, height));
			body = new Box[]{new Box(0, top, width, height)};

			// Squeezed from top to bottom when short of room; any room to spare stays above it.
			float s = u * Math.min(1, (height - top) / (DESIGN * u));
			float y = height - DESIGN * s + TOP * s;

			padR = PAD * s / 2;
			padX = width / 2;
			padY = y + padR;
			ringGap = 0.12f * s;

			// The keys beside the pad reach in under it; the pad is cut out of them when drawn,
			// which shapes them around it as on the phones of the time.
			float side = SIDE * u;
			float inner = padX - padR * 0.5f;
			boxes[SOFT_LEFT] = new Box(side, y, inner, y + SOFT * s);
			boxes[SOFT_RIGHT] = new Box(width - inner, y, width - side, y + SOFT * s);
			float callBottom = padY + padR;
			boxes[CALL] = new Box(side, callBottom - CALL_HEIGHT * s, inner, callBottom);
			boxes[END] = new Box(width - inner, callBottom - CALL_HEIGHT * s, width - side, callBottom);

			float gapX = KEY_GAP_X * u;
			float gapY = KEY_GAP * s;
			float keyW = (width - 2 * side - 2 * gapX) / 3;
			float keyH = KEY * s;
			float y0 = y + (PAD + PAD_GAP) * s;
			for (int i = 0; i < 12; i++) {
				float x = side + (i % 3) * (keyW + gapX);
				float ky = y0 + (i / 3) * (keyH + gapY);
				boxes[i] = new Box(x, ky, x + keyW, ky + keyH);
			}
			slop = Math.min(gapX, gapY) / 2;
		}
		okR = padR * OK_RATIO;
	}

	/** The part under a touch, or {@link #NONE}. The pad comes first, as keys reach in under it. */
	int partAt(float x, float y) {
		int pad = padPartAt(x - padX, y - padY);
		if (pad != NONE) {
			return pad;
		}
		int found = NONE;
		float nearest = Float.MAX_VALUE;
		for (int i = 0; i < BOXES; i++) {
			Box box = boxes[i];
			if (x < box.left - slop || x > box.right + slop || y < box.top - slop || y > box.bottom + slop) {
				continue;
			}
			float dx = x - box.centerX();
			float dy = y - box.centerY();
			float distance = dx * dx + dy * dy;
			if (distance < nearest) {
				nearest = distance;
				found = i;
			}
		}
		return found;
	}

	/** The piece of the pad at this offset from its center, or {@link #NONE} off the pad. */
	int padPartAt(float dx, float dy) {
		float distance = (float) Math.hypot(dx, dy);
		if (distance > padR + slop) {
			return NONE;
		}
		if (distance <= okR) {
			return FIRE;
		}
		double angle = Math.toDegrees(Math.atan2(dx, -dy));
		if (angle < 0) {
			angle += 360;
		}
		for (int i = 0; i < SECTORS.length; i++) {
			double from = SECTORS[i][0];
			double offset = angle - from;
			if (offset < 0) {
				offset += 360;
			}
			if (offset < SECTORS[i][1]) {
				return UP + i;
			}
		}
		return UP; // not reached: the sectors cover the circle
	}

	/** Where the label of a box goes: for the keys cut around the pad, the middle of what is left. */
	float labelX(int part) {
		Box box = boxes[part];
		if (landscape) {
			return box.centerX();
		}
		float cut = padR + ringGap;
		return switch (part) {
			case SOFT_LEFT, CALL -> (box.left + padX - cut) / 2;
			case SOFT_RIGHT, END -> (padX + cut + box.right) / 2;
			default -> box.centerX();
		};
	}

	/** Whether this box is shaped around the pad. */
	boolean aroundPad(int part) {
		return !landscape && part >= SOFT_LEFT && part <= END;
	}

	private static float minKeypadHeight(float width, float height) {
		return Math.min(SQUEEZED * width / 6, 0.40f * height);
	}

	private static float maxKeypadHeight(float width, float height) {
		return Math.min(DESIGN * width / 6, 0.45f * height);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	/** A rectangle; android.graphics.RectF is not available in plain unit tests. */
	static final class Box {
		final float left;
		final float top;
		final float right;
		final float bottom;

		Box(float left, float top, float right, float bottom) {
			this.left = left;
			this.top = top;
			this.right = right;
			this.bottom = bottom;
		}

		float width() {
			return right - left;
		}

		float height() {
			return bottom - top;
		}

		float centerX() {
			return (left + right) / 2;
		}

		float centerY() {
			return (top + bottom) / 2;
		}

		boolean intersects(Box other) {
			return left < other.right && other.left < right && top < other.bottom && other.top < bottom;
		}

		boolean contains(Box other) {
			return left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom;
		}

		@Override
		public String toString() {
			return "[" + left + ", " + top + ", " + right + ", " + bottom + "]";
		}
	}
}
