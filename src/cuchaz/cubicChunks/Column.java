/*******************************************************************************
 * Copyright (c) 2014 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Jeff Martin - initial API and implementation
 ******************************************************************************/
package cuchaz.cubicChunks;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.command.IEntitySelector;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cuchaz.cubicChunks.accessors.ChunkAccessor;

public class Column extends Chunk
{
	private static final Logger log = LogManager.getLogger();
	
	private TreeMap<Integer,CubicChunk> m_cubicChunks;
	private ExtendedBlockStorage[] m_legacySegments;
	private LightIndex m_lightIndex;
	private int m_roundRobinLightUpdatePointer;
	private List<CubicChunk> m_roundRobinCubicChunks;
	private EntityContainer m_entities;
	
	public Column( World world, int x, int z )
	{
		// NOTE: this constructor is called by the chunk loader
		super( world, x, z );
		
		init();
	}
	
	public Column( World world, Block[] blocks, byte[] meta, int chunkX, int chunkZ )
    {
		// NOTE: this constructor is called by the chunk generator
		this( world, chunkX, chunkZ );
		
		init();
		
		int maxY = blocks.length/256; // 256 blocks per y-layer
		
		// for each block...
		for( int localX=0; localX<16; localX++ )
		{
			for( int localZ=0; localZ<16; localZ++ )
			{
				for( int blockY=0; blockY<maxY; blockY++ )
				{
					int blockIndex = localX*maxY*16 | localZ*maxY | blockY;
					Block block = blocks[blockIndex];
					if( block != null && block != Blocks.air )
					{
						// get the cubic chunk
						int chunkY = Coords.blockToChunk( blockY );
						CubicChunk cubicChunk = getOrCreateCubicChunk( chunkY );
						
						// save the block
						// NOTE: don't call CubicChunk.setBlock() during chunk loading!
						// it will send block events and cause bad things to happen
						int localY = Coords.blockToLocal( blockY );
						cubicChunk.setBlockSilently( localX, localY, localZ, block, meta[blockIndex] );
						
						// build the light index
						m_lightIndex.setOpacity( localX, blockY, localZ, block.getLightOpacity() );
					}
				}
			}
		}
		
		isModified = true;
    }
	
	private void init( )
	{
		m_cubicChunks = new TreeMap<Integer,CubicChunk>();
		m_legacySegments = null;
		m_lightIndex = new LightIndex();
		m_roundRobinLightUpdatePointer = 0;
		m_roundRobinCubicChunks = new ArrayList<CubicChunk>();
		m_entities = new EntityContainer();
		
		// make sure no one's using data structures that have been replaced
		setStorageArrays( null );
		heightMap = null;
	}
	
	public long getAddress( )
	{
		return AddressTools.getAddress( xPosition, zPosition );
	}
	
	public World getWorld( )
	{
		return worldObj;
	}

	public int getX( )
	{
		return xPosition;
	}

	public int getZ( )
	{
		return zPosition;
	}
	
	public EntityContainer getEntityContainer( )
	{
		return m_entities;
	}
	
	public LightIndex getLightIndex( )
	{
		return m_lightIndex;
	}
	
	public Iterable<CubicChunk> cubicChunks( )
	{
		return m_cubicChunks.values();
	}
	
	public boolean hasCubicChunks( )
	{
		return !m_cubicChunks.isEmpty();
	}
	
	public CubicChunk getCubicChunk( int y )
	{
		return m_cubicChunks.get( y );
	}
	
	public CubicChunk getOrCreateCubicChunk( int y )
	{
		CubicChunk cubicChunk = m_cubicChunks.get( y );
		if( cubicChunk == null )
		{
			cubicChunk = addEmptyCubicChunk( y );
		}
		return cubicChunk;
	}
	
	public Iterable<CubicChunk> getCubicChunks( int minY, int maxY )
	{
		return m_cubicChunks.subMap( minY, true, maxY, true ).values();
	}
	
	public void addCubicChunk( CubicChunk cubicChunk )
	{
		m_cubicChunks.put( cubicChunk.getY(), cubicChunk );
		m_legacySegments = null;
	}
	
	private CubicChunk addEmptyCubicChunk( int chunkY )
	{
		// is there already a chunk here?
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			log.warn( String.format( "Column (%d,%d) already has cubic chunk at %d!", xPosition, zPosition, chunkY ) );
			return cubicChunk;
		}
		
		// make a new empty chunk
		cubicChunk = new CubicChunk( worldObj, this, xPosition, chunkY, zPosition, !worldObj.provider.hasNoSky );
		addCubicChunk( cubicChunk );
		return cubicChunk;
	}
	
	public CubicChunk removeCubicChunk( int chunkY )
	{
		m_legacySegments = null;
		return m_cubicChunks.remove( chunkY );
	}
	
	public List<RangeInt> getCubicChunkYRanges( )
	{
		return getRanges( m_cubicChunks.keySet() );
	}
	
	@Override
	public boolean needsSaving( boolean alwaysTrue )
	{
		return m_entities.needsSaving( worldObj.getTotalWorldTime() ) || isModified;
	}
	
	public void markSaved( )
	{
		m_entities.markSaved( worldObj.getTotalWorldTime() );
		isModified = false;
	}
	
	@Override //      getBlock
	public Block func_150810_a( final int localX, final int blockY, final int localZ )
	{
		// pass off to the cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localY = Coords.blockToLocal( blockY );
			return cubicChunk.getBlock( localX, localY, localZ );
		}
		
		// this cubic chunk isn't loaded, but there's something non-transparent there, return a block proxy
		int opacity = getLightIndex().getOpacity( localX, blockY, localZ );
		if( opacity > 0 )
		{
			return LightIndexBlockProxy.get( opacity );
		}
		
		return Blocks.air;
	}
	
	@Override //        setBlock
	public boolean func_150807_a( int localX, int blockY, int localZ, Block block, int meta )
	{
		// is there a chunk for this block?
		int chunkY = Coords.blockToChunk( blockY );
		boolean createdNewCubicChunk = false;
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk == null )
		{
			if( block == Blocks.air )
			{
				return false;
			}
			
			// make a new chunk for the block
			cubicChunk = addEmptyCubicChunk( chunkY );
			createdNewCubicChunk = true;
		}
		
		// pass off to chunk
		int localY = Coords.blockToLocal( blockY );
		Block oldBlock = cubicChunk.getBlock( localX, localY, localZ );
		boolean changed = cubicChunk.setBlock( localX, localY, localZ, block, meta );
		if( !changed )
		{
			return false;
		}
		
		// NOTE: the light index doesn't get updated here
		// it gets updated during the lighting update
		
		// update rain map
		// NOTE: precipitationHeightMap[xzCoord] is he lowest block that will contain rain
		// so precipitationHeightMap[xzCoord] - 1 is the block that is being rained on
		int xzCoord = localZ << 4 | localX;
		if( blockY >= precipitationHeightMap[xzCoord] - 1 )
		{
			// invalidate the rain height map value
			precipitationHeightMap[xzCoord] = -999;
		}
		
		// handle lighting updates
		if( createdNewCubicChunk )
		{
			// update light index before sky light update
			getLightIndex().setOpacity( localX, blockY, localZ, block.getLightOpacity() );
			
			// new chunk, update sky lighting
			generateSkylightMap();
		}
		else
		{
			int newOpacity = block.getLightOpacity();
			int oldOpacity = oldBlock.getLightOpacity();
			
			// did the top non-transparent block change?
			int oldMaxY = getHeightValue( localX, localZ );
			getLightIndex().setOpacity( localX, blockY, localZ, newOpacity );
			int newMaxY = getHeightValue( localX, localZ );
			if( oldMaxY != newMaxY )
			{
				updateBlockSkylight( localX, localZ, oldMaxY, newMaxY );
			}
			
			// if opacity changed and ( opacity decreased or block now has any light )
			int skyLight = getSavedLightValue( EnumSkyBlock.Sky, localX, blockY, localZ );
			int blockLight = getSavedLightValue( EnumSkyBlock.Block, localX, blockY, localZ );
			if( newOpacity != oldOpacity && ( newOpacity < oldOpacity || skyLight > 0 || blockLight > 0 ) )
			{
				ChunkAccessor.propagateSkylightOcclusion( this, localX, localZ );
			}
		}
		
		// update lighting index
		getLightIndex().setOpacity( localX, blockY, localZ, block.getLightOpacity() );
		
		isModified = true;
		
		return true;
	}
	
	@Override
	public int getBlockMetadata( int localX, int blockY, int localZ )
	{
		// pass off to the cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localY = Coords.blockToLocal( blockY );
			return cubicChunk.getBlockMetadata( localX, localY, localZ );
		}
		return 0;
	}
	
	@Override
	public boolean setBlockMetadata( int localX, int blockY, int localZ, int meta )
	{
		// pass off to the cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localY = Coords.blockToLocal( blockY );
			return cubicChunk.setBlockMetadata( localX, localY, localZ, meta );
		}
		return false;
	}
	
	@Override
	public ExtendedBlockStorage[] getBlockStorageArray( )
	{
		if( m_legacySegments == null )
		{
			// build the segments index
			if( m_cubicChunks.isEmpty() )
			{
				m_legacySegments = new ExtendedBlockStorage[0];
			}
			else
			{
				m_legacySegments = new ExtendedBlockStorage[m_cubicChunks.lastKey()+1];
				for( CubicChunk cubicChunk : m_cubicChunks.values() )
				{
					m_legacySegments[cubicChunk.getY()] = cubicChunk.getStorage();
				}
			}
		}
		return m_legacySegments;
	}
	
	public int getTopCubicChunkY( )
	{
		int blockY = getLightIndex().getTopNonTransparentBlockY();
		return Coords.blockToChunk( blockY );
	}
	
	@Override
	public int getTopFilledSegment()
    {
		return Coords.chunkToMinBlock( getTopCubicChunkY() );
    }
	
	@Override
	public boolean getAreLevelsEmpty( int minBlockY, int maxBlockY )
	{
		int minChunkY = Coords.blockToChunk( minBlockY );
		int maxChunkY = Coords.blockToChunk( maxBlockY );
		for( int chunkY=minChunkY; chunkY<=maxChunkY; chunkY++ )
		{
			CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
			if( cubicChunk != null && cubicChunk.hasBlocks() )
			{
				return false;
			}
		}
		return true;
	}
	
	@Override
	public boolean canBlockSeeTheSky( int localX, int blockY, int localZ )
	{
		return blockY >= getHeightValue( localX, localZ );
	}
	
	@Override
	public int getHeightValue( int localX, int localZ )
	{
		// NOTE: the "height value" here is the height of the highest block whose block UNDERNEATH is non-transparent
		return getLightIndex().getTopNonTransparentBlock( localX, localZ ) + 1;
	}
	
	@Override //  getOpacity
	public int func_150808_b( int localX, int blockY, int localZ )
	{
		return getLightIndex().getOpacity( localX, blockY, localZ );
	}
	
	public Iterable<Entity> entities( )
	{
		return m_entities.entities();
	}
	
	@Override
	public void addEntity( Entity entity )
    {
		// make sure the y-coord is sane
		int chunkY = Coords.getChunkYForEntity( entity );
		if( chunkY < 0 )
		{
			return;
		}
		
		// pass off to the cubic chunk
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			cubicChunk.addEntity( entity );
		}
		else
		{
			// entities don't have to be in chunks, just add it directly to the column
			entity.addedToChunk = true;
			entity.chunkCoordX = xPosition;
			entity.chunkCoordY = MathHelper.floor_double( entity.posY/16 );
			entity.chunkCoordZ = zPosition;
	        
			m_entities.add( entity );
		}
    }
	
	@Override
	public void removeEntity( Entity entity )
	{
		removeEntityAtIndex( entity, entity.chunkCoordY );
	}
	
	@Override
	public void removeEntityAtIndex( Entity entity, int chunkY )
	{
		if( m_entities.remove( entity ) )
		{
			isModified = true;
		}
		
		// pass off to the cubic chunk
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			cubicChunk.removeEntity( entity );
		}
	}
	
	@Override
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public void getEntitiesOfTypeWithinAAAB( Class c, AxisAlignedBB queryBox, List out, IEntitySelector selector )
	{
		// get a y-range that 2 blocks wider than the box for safety
		int minChunkY = Coords.blockToChunk( MathHelper.floor_double( queryBox.minY - 2 ) );
		int maxChunkY = Coords.blockToChunk( MathHelper.floor_double( queryBox.maxY + 2 ) );
		for( CubicChunk cubicChunk : getCubicChunks( minChunkY, maxChunkY ) )
		{
			cubicChunk.getEntities( (List<Entity>)out, c, queryBox, selector );
		}
		
		// check the column too
		m_entities.getEntities( out, c, queryBox, selector );
	}
	
	@Override
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public void getEntitiesWithinAABBForEntity( Entity excludedEntity, AxisAlignedBB queryBox, List out, IEntitySelector selector )
	{
		// get a y-range that 2 blocks wider than the box for safety
		int minChunkY = Coords.blockToChunk( MathHelper.floor_double( queryBox.minY - 2 ) );
		int maxChunkY = Coords.blockToChunk( MathHelper.floor_double( queryBox.maxY + 2 ) );
		for( CubicChunk cubicChunk : getCubicChunks( minChunkY, maxChunkY ) )
		{
			cubicChunk.getEntitiesExcept( (List<Entity>)out, excludedEntity, queryBox, selector );
		}
		
		// check the column too
		m_entities.getEntitiesExcept( out, excludedEntity, queryBox, selector );
	}
	
	@Override //      getTileEntity
	public TileEntity func_150806_e( int localX, int blockY, int localZ )
	{
		// pass off to the cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localY = Coords.blockToLocal( blockY );
			return cubicChunk.getTileEntity( localX, localY, localZ );
		}
		return null;
	}
	
	@Override
	@SuppressWarnings( "unchecked" )
	public void addTileEntity( TileEntity tileEntity )
	{
		// NOTE: this is called only by the chunk loader
		
		int blockX = tileEntity.field_145851_c;
		int blockY = tileEntity.field_145848_d;
		int blockZ = tileEntity.field_145849_e;
		
		// pass off to the cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localX = Coords.blockToLocal( blockX );
			int localY = Coords.blockToLocal( blockY );
			int localZ = Coords.blockToLocal( blockZ );
			cubicChunk.addTileEntity( localX, localY, localZ, tileEntity );
		}
		
		if( isChunkLoaded )
		{
			// was the tile entity actually added?
			if( tileEntity.hasWorldObj() )
			{
				// tell the world
				worldObj.field_147482_g.add( tileEntity );
			}
		}
	}
	
	@Override // addTileEntity
	public void func_150812_a( int localX, int blockY, int localZ, TileEntity tileEntity )
	{
		// NOTE: this is called when the world sets this block
		
		// pass off to the cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localY = Coords.blockToLocal( blockY );
			cubicChunk.addTileEntity( localX, localY, localZ, tileEntity );
		}
		else
		{
			log.warn( String.format( "No cubic chunk at (%d,%d,%d) to add tile entity (block %d,%d,%d)!",
				xPosition, chunkY, zPosition,
				tileEntity.field_145851_c, blockY, tileEntity.field_145849_e
			) );
		}
	}
	
	@Override
	public void removeTileEntity( int localX, int blockY, int localZ )
	{
		if( isChunkLoaded )
		{
			// pass off to the cubic chunk
			int chunkY = Coords.blockToChunk( blockY );
			CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
			if( cubicChunk != null )
			{
				int localY = Coords.blockToLocal( blockY );
				cubicChunk.removeTileEntity( localX, localY, localZ );
			}
		}
	}
	
	@Override
	public void onChunkLoad( )
	{
		isChunkLoaded = true;
	}
	
	@Override
	public void onChunkUnload( )
	{
		isChunkLoaded = false;
	}
	
	public byte[] encode( boolean isFirstTime )
	throws IOException
	{
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream( buf );
		// NOTE: there's no need to do compression here. This output is compressed later
		
		// how many cubic chunks are we sending?
		int numCubicChunks = 0;
		for( @SuppressWarnings( "unused" ) CubicChunk cubicChunk : cubicChunks() )
		{
			numCubicChunks++;
		}
		out.writeShort( numCubicChunks );
		
		// send the actual cubic chunk data
		for( CubicChunk cubicChunk : cubicChunks() )
		{
			// signal we're sending this cubic chunk
			out.writeShort( cubicChunk.getY() );
			
			ExtendedBlockStorage storage = cubicChunk.getStorage();
			
			// 1. block IDs, low bits
			out.write( storage.getBlockLSBArray() );
			
			// 2. block IDs, high bits
			if( storage.getBlockMSBArray() != null )
			{
				out.writeByte( 1 );
				out.write( storage.getBlockMSBArray().data );
			}
			else
			{
				// signal we're not sending this data
				out.writeByte( 0 );
			}
			
			// 3. metadata
			out.write( storage.getMetadataArray().data );
			
			// 4. block light
			out.write( storage.getBlocklightArray().data );
			
			if( !worldObj.provider.hasNoSky )
			{
				// 5. sky light
				out.write( storage.getSkylightArray().data );
			}
			
			if( isFirstTime )
			{
				// 6. biomes
				out.write( getBiomeArray() );
			}
		}
		
		// 7. light index
		getLightIndex().writeData( out );
		
		out.close();
		return buf.toByteArray();
	}
	
	@Override
	public void fillChunk( byte[] data, int segmentsToCopyBitFlags, int blockMSBToCopyBitFlags, boolean isFirstTime )
	{
		// NOTE: this is called on the client when it receives chunk data from the server
		
		ByteArrayInputStream buf = new ByteArrayInputStream( data );
		DataInputStream in = new DataInputStream( buf );
		
		try
		{
			// how many cubic chunks are we reading?
			int numCubicChunks = in.readUnsignedShort();
			for( int i=0; i<numCubicChunks; i++ )
			{
				int chunkY = in.readUnsignedShort();
				CubicChunk cubicChunk = getOrCreateCubicChunk( chunkY );
				
				ExtendedBlockStorage storage = cubicChunk.getStorage();
				
				// 1. block IDs, low bits
				in.read( storage.getBlockLSBArray() );
				
				// 2. block IDs, high bits
				boolean isHighBitsAttached = in.readByte() != 0;
				if( isHighBitsAttached )
				{
					if( storage.getBlockMSBArray() == null )
					{
						storage.createBlockMSBArray();
					}
					in.read( storage.getBlockMSBArray().data );
				}
				
				// 3. metadata
				in.read( storage.getMetadataArray().data );
				
				// 4. block light
				in.read( storage.getBlocklightArray().data );
				
				if( !worldObj.provider.hasNoSky )
				{
					// 5. sky light
					in.read( storage.getSkylightArray().data );
				}
				
				if( isFirstTime )
				{
					// 6. biomes
					in.read( getBiomeArray() );
				}
				
				// clean up invalid blocks
				storage.removeInvalidBlocks();
			}
			
			// 7. light index
			getLightIndex().readData( in );
			
			in.close();
		}
		catch( IOException ex )
		{
			log.error( String.format( "Unable to read data for column (%d,%d)", xPosition, zPosition ), ex );
		}
		
		// update lighting flags
		isLightPopulated = true;
		isTerrainPopulated = true;
		
		// update tile entities in each chunk
		for( CubicChunk cubicChunk : m_cubicChunks.values() )
		{
			for( TileEntity tileEntity : cubicChunk.tileEntities() )
			{
				tileEntity.updateContainingBlockInfo();
			}
		}
	}
	
	@Override //         tick
	public void func_150804_b( boolean tryToTickFaster )
	{
		// tick-based lighting calculations
		if( ChunkAccessor.isGapLightingUpdated( this ) && !worldObj.provider.hasNoSky && !tryToTickFaster )
		{
			ChunkAccessor.recheckGaps( this, worldObj.isClient );
		}
		
		// isTicked
		field_150815_m = true;
		
		// populate lighting
		if( !isLightPopulated && isTerrainPopulated )
		{
			func_150809_p();
		}
		
		// migrate moved entities to new cubic chunks
		// UNDONE: optimize out the new
		List<Entity> entities = new ArrayList<Entity>();
		for( CubicChunk cubicChunk : m_cubicChunks.values() )
		{
			cubicChunk.getMigratedEntities( entities );
			for( Entity entity : entities )
			{
				int chunkX = Coords.getChunkXForEntity( entity );
				int chunkY = Coords.getChunkYForEntity( entity );
				int chunkZ = Coords.getChunkZForEntity( entity );
				
				if( chunkX != xPosition || chunkZ != zPosition )
				{
					// Unfortunately, entities get updated after chunk ticks
					// that means entities might appear to be in the wrong column this tick,
					// but they'll be corrected before the next tick during column migration
					// so we can safely ignore them
					continue;
				}
				
				// try to find the new cubic chunk for this entity
				cubicChunk.removeEntity( entity );
				CubicChunk newCubicChunk = m_cubicChunks.get( chunkY );
				if( newCubicChunk != null )
				{
					// move the entity to the new cubic chunk
					newCubicChunk.addEntity( entity );
				}
				else
				{
					// move the entity to the column
					addEntity( entity );
				}
			}
		}
		
		// UNDONE: check for entity migration from the column to a cubic chunk
	}
	
	@Override
	public void generateSkylightMap()
    {
		// NOTE: this is called right after chunk generation, and right after any new segments are created
		
		// init the rain map to -999, which is a kind of null value
		// this array is actually a cache
		// values will be calculated by the getter
		for( int localX=0; localX<16; localX++ )
		{
			for( int localZ=0; localZ<16; localZ++ )
			{
				precipitationHeightMap[localX + (localZ << 4)] = -999;
			}
		}
		
		if( !worldObj.provider.hasNoSky )
		{
			int maxBlockY = getTopFilledSegment() + 15;
			int minBlockY = Coords.chunkToMinBlock( m_cubicChunks.firstKey() );
			
			// build the skylight map
			for( int localX=0; localX<16; localX++ )
			{
				for( int localZ=0; localZ<16; localZ++ )
				{
					// start with full light for this block
					int lightValue = 15;
					
					// start with the top block and fall down
					for( int blockY=maxBlockY; blockY>=minBlockY; blockY-- )
					{
						// light opacity is [0,255], all blocks 0, 255 except ice,water:3, web:1
						int lightOpacity = func_150808_b( localX, blockY, localZ );
						if( lightOpacity == 0 && lightValue != 15 )
						{
							// after something blocks light, apply a linear falloff
							lightOpacity = 1;
						}
						
						// decrease the light
						lightValue -= lightOpacity;
						
						// stop when we run out of light
						if( lightValue <= 0 )
						{
							break;
						}
						
						// update the cubic chunk only if it's actually loaded
						CubicChunk cubicChunk = m_cubicChunks.get( Coords.blockToChunk( blockY ) );
						if( cubicChunk != null )
						{
							// save the sky light value
							int localY = Coords.blockToLocal( blockY );
							cubicChunk.setLightValue( EnumSkyBlock.Sky, localX, localY, localZ, lightValue );
							
							// signal a render update
							int blockX = Coords.localToBlock( xPosition, localX );
							int blockZ = Coords.localToBlock( zPosition, localZ );
							worldObj.func_147479_m( blockX, blockY, blockZ );
						}
					}
				}
			}
		}
    }
	
	@Override
	public int getPrecipitationHeight( int localX, int localZ )
	{
		// UNDONE: update this calculation to use better data structures
		
		int xzCoord = localX | localZ << 4;
		int height = this.precipitationHeightMap[xzCoord];
		if( height == -999 )
		{
			// compute a new rain height
			
			// TEMP
			if( m_cubicChunks.isEmpty() )
			{
				System.out.println( String.format( "No cubic chunks in column (%d,%d)", xPosition, zPosition ) );
			}
			
			int maxBlockY = getTopFilledSegment() + 15;
			int minBlockY = Coords.chunkToMinBlock( m_cubicChunks.firstKey() );
			
			height = -1;
			
			for( int blockY=maxBlockY; blockY>=minBlockY; blockY-- )
			{
				Block block = this.func_150810_a( localX, maxBlockY, localZ );
				Material material = block.getMaterial();
				
				if( material.blocksMovement() || material.isLiquid() )
				{
					height = maxBlockY + 1;
					break;
				}
			}
			
			precipitationHeightMap[xzCoord] = height;
			
			isModified = true;
		}
		
		return height;
	}
	
	@Override
	public int getBlockLightValue( int localX, int blockY, int localZ, int skylightSubtracted )
	{
		// NOTE: this is called by WorldRenderers
		
		// pass off to cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localY = Coords.blockToLocal( blockY );
			int light = cubicChunk.getBlockLightValue( localX, localY, localZ, skylightSubtracted );
			
			if( light > 0 )
	        {
	            isLit = true;
	        }
			
			return light;
		}
		
		// defaults
		if( !worldObj.provider.hasNoSky && skylightSubtracted < EnumSkyBlock.Sky.defaultLightValue )
		{
			return EnumSkyBlock.Sky.defaultLightValue - skylightSubtracted;
		}
		return 0;
	}
	
	@Override
	public int getSavedLightValue( EnumSkyBlock lightType, int localX, int blockY, int localZ )
	{
		// NOTE: this is the light function that is called by the rendering code on client
		
		// pass off to cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localY = Coords.blockToLocal( blockY );
			return cubicChunk.getLightValue( lightType, localX, localY, localZ );
		}
		
		// there's no cubic chunk, rely on defaults
		if( canBlockSeeTheSky( localX, blockY, localZ ) )
		{
			return lightType.defaultLightValue;
		}
		else
		{
			return 0;
		}
	}
	
	@Override
	public void setLightValue( EnumSkyBlock lightType, int localX, int blockY, int localZ, int light )
	{
		// pass off to cubic chunk
		int chunkY = Coords.blockToChunk( blockY );
		CubicChunk cubicChunk = m_cubicChunks.get( chunkY );
		if( cubicChunk != null )
		{
			int localY = Coords.blockToLocal( blockY );
			cubicChunk.setLightValue( lightType, localX, localY, localZ, light );
			
			isModified = true;
		}
	}
	
	private void updateBlockSkylight( int localX, int localZ, int oldMaxY, int newMaxY )
	{
		// NOTE: this calls World.updateLightByType( sky ) for each block in the column
		worldObj.markBlocksDirtyVertical(
			localX + this.xPosition * 16,
			localZ + this.zPosition * 16,
			newMaxY, oldMaxY // it's ok if these are out of order, the method will swap them if they are
		);
		
		int blockX = Coords.localToBlock( xPosition, localX );
		int blockZ = Coords.localToBlock( zPosition, localZ );
		
		if( !worldObj.provider.hasNoSky )
		{
			// update sky light
			
			// sort the y values into order bounds
			int lowerY = oldMaxY;
			int upperY = newMaxY;
			if( newMaxY < oldMaxY )
			{
				lowerY = newMaxY;
				upperY = oldMaxY;
			}
			
			// reset sky light for the affected y range
			for( int blockY=lowerY; blockY<upperY; blockY++ )
			{
				// did we add sky or remove sky?
				int light = newMaxY < oldMaxY ? 15 : 0;
				
				// save the light value
				int chunkY = Coords.blockToChunk( blockY );
				CubicChunk cubicChunk = getCubicChunk( chunkY );
				if( cubicChunk != null )
				{
					int localY = Coords.blockToLocal( blockY );
					cubicChunk.setLightValue( EnumSkyBlock.Sky, localX, localY, localZ, light );
				}
				
				// mark the block for a render update
				worldObj.func_147479_m( blockX, blockY, blockZ );
			}
			
			// compute the skylight falloff starting just under the new top block
			int light = 15;
			for( int blockY=newMaxY-1; blockY > 0; blockY-- )
			{
				// get the opacity to apply for this block
				int lightOpacity = Math.max( 1, func_150808_b( localX, blockY, localZ ) );
				
				// compute the falloff
				light = Math.max( light - lightOpacity, 0 );
				
				// save the light value
				int chunkY = Coords.blockToChunk( blockY );
				CubicChunk cubicChunk = getCubicChunk( chunkY );
				if( cubicChunk != null )
				{
					int localY = Coords.blockToLocal( blockY );
					cubicChunk.setLightValue( EnumSkyBlock.Sky, localX, localY, localZ, light );
				}
				
				if( light == 0 )
				{
					// we ran out of light
					break;
				}
			}
			
			// update this block and its xz neighbors
			updateSkylightForYBlocks( blockX - 1, blockZ, lowerY, upperY );
			updateSkylightForYBlocks( blockX + 1, blockZ, lowerY, upperY );
			updateSkylightForYBlocks( blockX, blockZ - 1, lowerY, upperY );
			updateSkylightForYBlocks( blockX, blockZ + 1, lowerY, upperY );
			updateSkylightForYBlocks( blockX, blockZ, lowerY, upperY );
			
			// NOTE: after this, World calls updateLights on the source block which changes light values
		}
		
		isModified = true;
	}
	
	private void updateSkylightForYBlocks( int blockX, int blockZ, int minBlockY, int maxBlockY )
	{
		if( maxBlockY > minBlockY && worldObj.doChunksNearChunkExist( blockX, 0, blockZ, 16 ) )
		{
			for( int y=minBlockY; y<maxBlockY; y++ )
			{
				worldObj.updateLightByType( EnumSkyBlock.Sky, blockX, y, blockZ );
			}
			
			isModified = true;
		}
	}
	
	protected List<RangeInt> getRanges( Iterable<Integer> yValues )
	{
		// compute a kind of run-length encoding on the cubic chunk y-values
		List<RangeInt> ranges = new ArrayList<RangeInt>();
		Integer start = null;
		Integer stop = null;
		for( int chunkY : yValues )
		{
			if( start == null )
			{
				// start a new range
				start = chunkY;
				stop = chunkY;
			}
			else if( chunkY == stop + 1 )
			{
				// extend the range
				stop = chunkY;
			}
			else
			{
				// end the range
				ranges.add( new RangeInt( start, stop ) );
				
				// start a new range
				start = chunkY;
				stop = chunkY;
			}
		}
		
		if( start != null )
		{
			// finish the last range
			ranges.add( new RangeInt( start, stop ) );
		}
		
		return ranges;
	}
	
	@Override
	public void resetRelightChecks( )
	{
		m_roundRobinLightUpdatePointer = 0;
		m_roundRobinCubicChunks.clear();
		m_roundRobinCubicChunks.addAll( m_cubicChunks.values() );
	}
	
	@Override // doSomeRoundRobinLightUpdates
	public void enqueueRelightChecks( )
	{
		if( m_roundRobinCubicChunks.isEmpty() )
		{
			resetRelightChecks();
		}
		
		// we get 8 updates this time
		for( int i=0; i<8; i++ )
		{
			// once we've checked all the blocks, stop checking
			int maxPointer = 16*16*m_roundRobinCubicChunks.size();
			if( m_roundRobinLightUpdatePointer >= maxPointer )
			{
				return;
			}
			
			// get this update's arguments
			int cubicChunkIndex = Bits.unpackUnsigned( m_roundRobinLightUpdatePointer, 4, 8 );
			int localX = Bits.unpackUnsigned( m_roundRobinLightUpdatePointer, 4, 4 );
			int localZ = Bits.unpackUnsigned( m_roundRobinLightUpdatePointer, 4, 0 );
			
			// advance to the next block
			// this pointer advances over segment block columns
			// starting from the block columns in the bottom segment and moving upwards
			m_roundRobinLightUpdatePointer++;
			
			// get the cubic chunk that was pointed to
			CubicChunk cubicChunk = m_roundRobinCubicChunks.get( cubicChunkIndex );
			
			int blockX = Coords.localToBlock( xPosition, localX );
			int blockZ = Coords.localToBlock( zPosition, localZ );
			
			// for each block in this segment block column...
			for( int localY=0; localY<16; ++localY )
			{
				if( cubicChunk.getBlock( localX, localY, localZ ).getMaterial() == Material.air )
				{
					int blockY = Coords.localToBlock( cubicChunkIndex, localY );
					
					// if there's a light source next to this block, update the light source
					if( worldObj.getBlock( blockX, blockY - 1, blockZ ).getLightValue() > 0 )
					{
						worldObj.func_147451_t( blockX, blockY - 1, blockZ );
					}
					if( worldObj.getBlock( blockX, blockY + 1, blockZ ).getLightValue() > 0 )
					{
						worldObj.func_147451_t( blockX, blockY + 1, blockZ );
					}
					if( worldObj.getBlock( blockX - 1, blockY, blockZ ).getLightValue() > 0 )
					{
						worldObj.func_147451_t( blockX - 1, blockY, blockZ );
					}
					if( worldObj.getBlock( blockX + 1, blockY, blockZ ).getLightValue() > 0 )
					{
						worldObj.func_147451_t( blockX + 1, blockY, blockZ );
					}
					if( worldObj.getBlock( blockX, blockY, blockZ - 1 ).getLightValue() > 0 )
					{
						worldObj.func_147451_t( blockX, blockY, blockZ - 1 );
					}
					if( worldObj.getBlock( blockX, blockY, blockZ + 1 ).getLightValue() > 0 )
					{
						worldObj.func_147451_t( blockX, blockY, blockZ + 1 );
					}
					
					// then update this block
					worldObj.func_147451_t( blockX, blockY, blockZ );
				}
			}
		}
	}
}
