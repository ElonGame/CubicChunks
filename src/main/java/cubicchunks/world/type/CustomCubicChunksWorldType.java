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
package cubicchunks.world.type;

import cubicchunks.world.ICubicWorldServer;
import cubicchunks.worldgen.generator.ICubeGenerator;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldType;

public class CustomCubicChunksWorldType extends WorldType implements ICubicWorldType {

	public CustomCubicChunksWorldType() {
		super("CustomCubic");
	}

	public static void create() {
		new CustomCubicChunksWorldType();
	}

	@Override
	public ICubeGenerator createCubeGenerator(ICubicWorldServer world) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public WorldProvider getReplacedProviderFor(WorldProvider provider) {
		return provider; // TODO: Custom Nether? Custom End????
	}

	//TODO: Custom Cubic generator
	/*
	@Override public ICubicChunkGenerator createCubeGenerator(ICubicWorldServer world) {
		CubeProcessor terrain = new CustomTerrainProcessor(world);
		CubeProcessor features = new CustomFeatureProcessor();
		CubeProcessor population = new CustomPopulationProcessor(world);
		return new ICubicChunkGenerator() {
			@Override public void generateTerrain(Cube cube) {
				terrain.calculate(cube);
				features.calculate(cube);
				cube.initSkyLight();
			}

			@Override public void populateCube(Cube cube) {
				population.calculate(cube);
			}
		};
	}*/
}
