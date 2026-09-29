package javax.microedition.lcdui.keyboard;

import static javax.microedition.lcdui.keyboard.ClassicKeypad.BOXES;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.CALL;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.DOWN;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.DOWN_LEFT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.DOWN_RIGHT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.END;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.FIRE;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.LEFT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.NONE;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.NUM_0;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.POUND;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.RIGHT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.SOFT_LEFT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.SOFT_RIGHT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.STAR;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.UP;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.UP_LEFT;
import static javax.microedition.lcdui.keyboard.ClassicKeypad.UP_RIGHT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import javax.microedition.lcdui.keyboard.ClassicKeypad.Box;

public class ClassicKeypadTest {
	/** Screens in pixels, standing; each is also tried lying down. */
	private static final int[][] SCREENS = {
			{1080, 2340}, // Galaxy S22
			{1080, 2400}, // Redmi Note 12S
			{720, 1600},
			{1080, 1920},
			{1600, 2560},
			{1768, 2208}, // a foldable, open
	};
	/** Game screens of the catalog; 0×0 is a game that takes whatever it is given. */
	private static final int[][] GAMES = {
			{128, 128}, {128, 160}, {176, 208}, {176, 220}, {208, 208}, {240, 320},
			{352, 416}, {360, 640}, {320, 240}, {400, 240}, {640, 360}, {0, 0},
	};

	@Test
	public void everyKeyFitsAroundTheGameWithoutCoveringIt() {
		for (int[] screen : SCREENS) {
			for (int turn = 0; turn < 2; turn++) {
				float w = turn == 0 ? screen[0] : screen[1];
				float h = turn == 0 ? screen[1] : screen[0];
				for (int[] size : GAMES) {
					String where = (int) w + "x" + (int) h + ", game " + size[0] + "x" + size[1];
					Box area = ClassicKeypad.gameArea(w, h, size[0], size[1]);
					assertTrue(where, area.width() > w / 3 && area.height() > h / 3);
					assertTrue(where, new Box(0, 0, w, h).contains(area));
					Box game = place(area, size[0], size[1]);
					checkLayout(where, w, h, game, ClassicKeypad.layout(w, h, game));
				}
			}
		}
	}

	@Test
	public void theGameGetsTheWholeWidthStandingAndTheWholeHeightLyingOnTheTestPhones() {
		for (int[] phone : new int[][]{{1080, 2340}, {1080, 2400}}) {
			Box standing = ClassicKeypad.gameArea(phone[0], phone[1], 240, 320);
			assertEquals(phone[0], standing.width(), 0.01f);
			assertTrue(standing.height() >= 1440);
			Box lying = ClassicKeypad.gameArea(phone[1], phone[0], 320, 240);
			assertEquals(phone[0], lying.height(), 0.01f);
			assertTrue(lying.width() >= 1440);
		}
	}

	@Test
	public void keysAreBigEnoughForThumbsOnTheTestPhones() {
		// About 16 pixels to the millimeter on both phones.
		for (int[] phone : new int[][]{{1080, 2340}, {1080, 2400}}) {
			ClassicKeypad standing = layout(phone[0], phone[1], 240, 320);
			for (int part = 0; part <= POUND; part++) {
				assertTrue(standing.boxes[part].width() >= 300);
				assertTrue(standing.boxes[part].height() >= 100);
			}
			for (int part = SOFT_LEFT; part <= END; part++) {
				assertTrue(standing.boxes[part].height() >= 100);
				assertTrue(2 * (standing.labelX(part) - standing.boxes[part].left) >= 250
						|| 2 * (standing.boxes[part].right - standing.labelX(part)) >= 250);
			}
			assertTrue(standing.padR * 2 >= 300);

			ClassicKeypad lying = layout(phone[1], phone[0], 320, 240);
			for (int part = 0; part <= POUND; part++) {
				assertTrue(lying.boxes[part].width() >= 110);
				assertTrue(lying.boxes[part].height() >= 160);
			}
			assertTrue(lying.padR * 2 >= 350);
		}
	}

	@Test
	public void touchesFindTheirKeys() {
		for (ClassicKeypad keypad : new ClassicKeypad[]{layout(1080, 2340, 240, 320), layout(2340, 1080, 320, 240)}) {
			for (int part = 0; part < BOXES; part++) {
				Box box = keypad.boxes[part];
				assertEquals(part, keypad.partAt(keypad.labelX(part), box.centerY()));
			}
			for (int part = 0; part <= POUND; part++) {
				// Within the gap around a key still counts.
				Box box = keypad.boxes[part];
				assertEquals(part, keypad.partAt(box.left - keypad.slop / 2, box.centerY()));
				assertEquals(part, keypad.partAt(box.right + keypad.slop / 2, box.centerY()));
			}
			float x = keypad.padX;
			float y = keypad.padY;
			float r = keypad.padR * 0.7f;
			assertEquals(FIRE, keypad.partAt(x, y));
			assertEquals(UP, keypad.partAt(x, y - r));
			assertEquals(RIGHT, keypad.partAt(x + r, y));
			assertEquals(DOWN, keypad.partAt(x, y + r));
			assertEquals(LEFT, keypad.partAt(x - r, y));
			float d = r * (float) Math.sqrt(0.5);
			assertEquals(UP_RIGHT, keypad.partAt(x + d, y - d));
			assertEquals(DOWN_RIGHT, keypad.partAt(x + d, y + d));
			assertEquals(DOWN_LEFT, keypad.partAt(x - d, y + d));
			assertEquals(UP_LEFT, keypad.partAt(x - d, y - d));
			// A thumb a little off an arrow still gets the arrow.
			assertEquals(UP, keypad.partAt(x + r * (float) Math.sin(Math.toRadians(25)),
					y - r * (float) Math.cos(Math.toRadians(25))));
			// The body around the keys presses nothing.
			assertEquals(NONE, keypad.partAt(1, keypad.body[0].top + 1));
		}
	}

	@Test
	public void theNumberKeysAreInPhoneOrder() {
		ClassicKeypad keypad = layout(1080, 2340, 240, 320);
		Box one = keypad.boxes[0];
		assertTrue(keypad.boxes[1].left > one.right && keypad.boxes[2].left > keypad.boxes[1].right);
		assertTrue(keypad.boxes[3].top > one.bottom && keypad.boxes[STAR].top > keypad.boxes[6].bottom);
		assertTrue(keypad.boxes[NUM_0].left > keypad.boxes[STAR].right);
		assertTrue(keypad.boxes[POUND].left > keypad.boxes[NUM_0].right);
		// Soft keys at the top corners, green on the left, red on the right.
		assertTrue(keypad.boxes[SOFT_LEFT].bottom < keypad.boxes[CALL].top);
		assertTrue(keypad.boxes[SOFT_RIGHT].bottom < keypad.boxes[END].top);
		assertTrue(keypad.boxes[CALL].right < keypad.boxes[END].left);
		assertTrue(keypad.boxes[SOFT_LEFT].top < keypad.padY && keypad.boxes[CALL].bottom > keypad.padY);
	}

	@Test
	public void theDirectionsShareThePadWithoutGaps() {
		ClassicKeypad keypad = layout(1080, 2340, 240, 320);
		int[] degrees = new int[ClassicKeypad.PARTS];
		for (int tenth = 0; tenth < 3600; tenth++) {
			double angle = Math.toRadians(tenth / 10.0 + 0.05);
			float r = (keypad.okR + keypad.padR) / 2;
			int part = keypad.padPartAt((float) (r * Math.sin(angle)), (float) (-r * Math.cos(angle)));
			assertNotEquals(NONE, part);
			assertNotEquals(FIRE, part);
			degrees[part]++;
		}
		for (int part : new int[]{UP, RIGHT, DOWN, LEFT}) {
			assertEquals(600, degrees[part]);
		}
		for (int part : new int[]{UP_RIGHT, DOWN_RIGHT, DOWN_LEFT, UP_LEFT}) {
			assertEquals(300, degrees[part]);
		}
	}

	@Test
	public void oddScreensStillGetAUsableLayout() {
		float[][] screens = {{240, 320}, {1000, 1000}, {3000, 1000}, {1000, 3000}, {320, 240}};
		for (float[] s : screens) {
			for (int[] size : GAMES) {
				String where = s[0] + "x" + s[1] + ", game " + size[0] + "x" + size[1];
				Box game = place(ClassicKeypad.gameArea(s[0], s[1], size[0], size[1]), size[0], size[1]);
				checkLayout(where, s[0], s[1], game, ClassicKeypad.layout(s[0], s[1], game));
			}
		}
		// A game bigger than the screen (not scaled down) does not break it either.
		ClassicKeypad keypad = ClassicKeypad.layout(1080, 2340, new Box(0, 0, 1080, 2340));
		assertTrue(keypad.padR > 0);
		assertTrue(keypad.boxes[POUND].bottom <= 2340);
	}

	private static void checkLayout(String where, float w, float h, Box game, ClassicKeypad keypad) {
		Box screen = new Box(0, 0, w, h);
		for (int i = 0; i < BOXES; i++) {
			Box box = keypad.boxes[i];
			String key = where + ", part " + i + " " + box;
			assertTrue(key, box.width() > 0 && box.height() > 0);
			assertTrue(key, screen.contains(box));
			assertFalse(key + " covers the game " + game, box.intersects(game));
			assertTrue(key, inBody(keypad, box));
			for (int j = i + 1; j < BOXES; j++) {
				assertFalse(key + " overlaps " + j, box.intersects(keypad.boxes[j]));
			}
			if (keypad.aroundPad(i)) {
				// Cut around the pad: what is left of it, where the label goes, is off the pad.
				float dx = keypad.labelX(i) - keypad.padX;
				float dy = box.centerY() - keypad.padY;
				assertTrue(key, Math.hypot(dx, dy) > keypad.padR + keypad.ringGap);
			} else {
				assertTrue(key + " under the pad", distance(box, keypad.padX, keypad.padY) > keypad.padR);
			}
		}
		Box pad = new Box(keypad.padX - keypad.padR, keypad.padY - keypad.padR,
				keypad.padX + keypad.padR, keypad.padY + keypad.padR);
		assertTrue(where + ", pad " + pad, keypad.padR > 0 && inBody(keypad, pad));
		assertTrue(where + ", pad over the game", distance(game, keypad.padX, keypad.padY) >= keypad.padR);
		assertTrue(where, keypad.okR > 0 && keypad.okR < keypad.padR);
		assertTrue(where, keypad.slop >= 0);
	}

	private static boolean inBody(ClassicKeypad keypad, Box box) {
		for (Box body : keypad.body) {
			if (body.contains(box)) {
				return true;
			}
		}
		return false;
	}

	/** From a point to the nearest point of a box (0 inside it). */
	private static double distance(Box box, float x, float y) {
		float dx = Math.max(Math.max(box.left - x, 0), x - box.right);
		float dy = Math.max(Math.max(box.top - y, 0), y - box.bottom);
		return Math.hypot(dx, dy);
	}

	private static ClassicKeypad layout(float w, float h, int gameWidth, int gameHeight) {
		Box area = ClassicKeypad.gameArea(w, h, gameWidth, gameHeight);
		return ClassicKeypad.layout(w, h, place(area, gameWidth, gameHeight));
	}

	/** Where the emulator puts the game by default: scaled to fit, at the top, centered across. */
	private static Box place(Box area, int gameWidth, int gameHeight) {
		if (gameWidth <= 0 || gameHeight <= 0) {
			return area;
		}
		float scale = Math.min(area.width() / gameWidth, area.height() / gameHeight);
		float width = gameWidth * scale;
		float left = area.left + (area.width() - width) / 2;
		return new Box(left, area.top, left + width, area.top + gameHeight * scale);
	}
}
