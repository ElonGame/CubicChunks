/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.util;

import cubicchunks.world.cube.Cube;

import java.util.Iterator;

/**
 * Hash table implementation for objects in a cartesian coordinate system.
 * @see XYZAddressable
 *
 * @param <T> class of the objects to be contained in this map
 */
public class XYZMap<T extends XYZAddressable> implements Iterable<T> {

	/**
	 * A larger prime number used as seed for hash calculation.
	 */
	private static final int HASH_SEED = 1183822147;


	/**
	 * backing array containing all elements of this map
	 */
	private XYZAddressable[] buckets;

	/**
	 * the current number of elements in this map
	 */
	private int size;

	/**
	 * the maximum permissible load of the storage array, after reaching it the array will be resized
	 */
	private float loadFactor;

	/**
	 * the load threshold of the storage array, after reaching it the array will be resized
	 */
	private int loadThreshold;

	/**
	 * binary mask used to wrap indices
	 */
	private int mask;


	/**
	 * Creates a new XYZMap with the given load factor and initial capacity. The map will automatically grow if
	 * the specified load is surpassed.
	 *
	 * @param loadFactor the load factor
	 * @param capacity the initial capacity
	 */
	public XYZMap(float loadFactor, int capacity) {

		if (loadFactor > 1.0) {
			throw new IllegalArgumentException("You really dont want to be using a " + loadFactor + " load loadFactor with this hash table!");
		}

		this.loadFactor = loadFactor;

		int tCapacity = 1;
		while (tCapacity < capacity) {
			tCapacity <<= 1;
		}
		this.buckets = new XYZAddressable[tCapacity];

		this.refreshFields();
	}


	/**
	 * Returns the number of elements in this map
	 *
	 * @return the number of elements in this map
	 */
	public int getSize() {
		return this.size;
	}


	/**
	 * Computes a 32b hash based on the given coordinates.
	 *
	 * @param x the x-coordinate
	 * @param y the y-coordinate
	 * @param z the z-coordinate
	 * @return a 32b hash based on the given coordinates
	 */
	private static int hash(int x, int y, int z){
		int hash = HASH_SEED;
		hash += x;
		hash *= HASH_SEED;
		hash += y;
		hash *= HASH_SEED;
		hash += z;
		hash *= HASH_SEED;
		return hash;
	}

	/**
	 * Computes the desired bucket's index for the given coordinates, based on the map's current capacity.
	 *
	 * @param x the x-coordinate
	 * @param y the y-coordinate
	 * @param z the z-coordinate
	 * @return the desired bucket's index for the given coordinates
	 */
	private int getIndex(int x, int y, int z) {
		return hash(x, y, z) & this.mask;
	}

	/**
	 * Computes the next index to the right of the given index, wrapping around if necessary.
	 *
	 * @param index the previous index
	 * @return the next index
	 */
	private int getNextIndex(int index) {
		return (index + 1) & this.mask;
	}


	/**
	 * Associates the given value with its xyz-coordinates. If the map previously contained a mapping for these
	 * coordinates, the old value is replaced.
	 *
	 * @param value value to be associated with its coordinates
	 * @return the previous value associated with the given value's coordinates or null if no such value exists
	 */
	@SuppressWarnings("unchecked")
	public T put(T value) {

		int x = value.getX();
		int y = value.getY();
		int z = value.getZ();
		int index = getIndex(x, y, z);

		// find the closest empty space or the element to be replaced
		XYZAddressable bucket = this.buckets[index];
		while (bucket != null) {

			// If there exists an element at the given element's position, overwrite it.
			if (bucket.getX() == x && bucket.getY() == y && bucket.getZ() == z) {
				this.buckets[index] = value;
				return (T) bucket;
			}

			index = getNextIndex(index);
			bucket = this.buckets[index];
		}

		// Insert the element into the empty bucket.
		this.buckets[index] = value;

		// If the load threshold has been reached, increase the map's size.
		++this.size;
		if (this.size > this.loadThreshold) {
			grow();
		}

		return null;
	}

	/**
	 * Removes and returns the entry associated with the given coordinates.
	 *
	 * @param x the x-coordinate
	 * @param y the y-coordinate
	 * @param z the z-coordinate
	 * @return the entry associated with the specified coordinates or null if no such value exists
	 */
	@SuppressWarnings("unchecked")
	public T remove(int x, int y, int z) {

		int index = getIndex(x, y, z);

		// Search for the element. Only the buckets from the element's supposed index up to the next free slot must
		// be checked.
		XYZAddressable bucket = this.buckets[index];
		while (bucket != null) {

			// If the correct bucket was found, remove it.
			if (bucket.getX() == x && bucket.getY() == y && bucket.getZ() == z) {
				--this.size;
				this.collapseBucket(index);
				return (T) bucket;
			}

			index = getNextIndex(index);
			bucket = this.buckets[index];
		}

		// nothing was removed
		return null;
	}

	/**
	 * Returns the value associated with the given coordinates or null if no such value exists.
	 *
	 * @param x the x-coordinate
	 * @param y the y-coordinate
	 * @param z the z-coordinate
	 * @return the entry associated with the specified coordinates or null if no such value exists
	 */
	@SuppressWarnings("unchecked")
	public T get(int x, int y, int z) {

		int index = getIndex(x, y, z);

		XYZAddressable bucket = this.buckets[index];
		while (bucket != null) {

			// If the correct bucket was found, return it.
			if (bucket.getX() == x && bucket.getY() == y && bucket.getZ() == z) {
				return (T) bucket;
			}

			index = getNextIndex(index);
			bucket = this.buckets[index];
		}

		// nothing was found
		return null;
	}


	/**
	 * Doubles the size of the backing array and redistributes all contained values accordingly.
	 */
	private void grow() {

		XYZAddressable[] oldBuckets = this.buckets;

		// double the size!
		this.buckets = new Cube[this.buckets.length*2];
		this.refreshFields();

		// Move the old entries to the new array.
		for (XYZAddressable oldBucket : oldBuckets) {

			// Skip empty buckets.
			if (oldBucket == null) {
				continue;
			}

			// Get the desired index of the old bucket and insert it into the first available slot.
			int index = getIndex(oldBucket.getX(), oldBucket.getY(), oldBucket.getZ());
			XYZAddressable bucket = this.buckets[index];
			while (bucket != null) {
				bucket = this.buckets[index = getNextIndex(index)];
			}
			this.buckets[index] = oldBucket;
		}
	}

	/**
	 * Removes the value contained at the given index by shifting suitable values on its right to the left.
	 *
	 * @param hole the index of the bucket to be collapsed
	 */
	private void collapseBucket(int hole) {

		int currentIndex = hole;
		while (true) {
			currentIndex = getNextIndex(currentIndex);

			// If there exists no element at the given index, there is nothing to fill the hole with.
			XYZAddressable cube = this.buckets[currentIndex];
			if (cube == null) {
				this.buckets[hole] = null;
				return;
			}

			// If the hole lies to the left of the currentIndex and to the right of the targetIndex, move the current
			// element. These if conditions are necessary due to the bucket array wrapping around.
			int targetIndex = getIndex(cube.getX(), cube.getY(), cube.getZ());

			// normal
			if (hole < currentIndex) {
				if (targetIndex <= hole || currentIndex < targetIndex) {
					this.buckets[hole] = cube;
					hole = currentIndex;
				}
			}

			// wrap around!
			else {
				if (hole >= targetIndex && targetIndex > currentIndex) {
					this.buckets[hole] = cube;
					hole = currentIndex;
				}
			}
		}
	}

	/**
	 * Updates the load threshold and the index mask based on the backing array's current size.
	 */
	private void refreshFields() {
		// we need that 1 extra space, make shore it will be there
		this.loadThreshold = Math.min(this.buckets.length - 1, (int) (this.buckets.length * this.loadFactor));
		this.mask = this.buckets.length - 1;
	}


	// Interface: Iterable<T> ------------------------------------------------------------------------------------------

	public Iterator<T> iterator() {

		int start;
		for (start = 0; start < this.buckets.length; start++) {
			if (this.buckets[start] != null) {
				break;
			}
		}

		final int f = start; // hacks just so I could use an anonymous class :P

		return new Iterator<T>() {
			int at = f;

			@Override
			public boolean hasNext() {
				return at < buckets.length;
			}

			@Override
			@SuppressWarnings("unchecked")
			public T next() {
				T ret = (T) buckets[at];
				for (at++; at < buckets.length; at++) {
					if (buckets[at] != null) {
						break;
					}
				}
				return ret;
			}
		};
	}

}