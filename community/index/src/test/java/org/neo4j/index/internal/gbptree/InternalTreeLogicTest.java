/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.index.internal.gbptree;

import org.apache.commons.lang3.mutable.MutableLong;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.test.rule.RandomRule;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import static org.neo4j.index.internal.gbptree.ConsistencyChecker.assertNoCrashOrBrokenPointerInGSPP;
import static org.neo4j.index.internal.gbptree.GenSafePointerPair.pointer;
import static org.neo4j.index.internal.gbptree.ValueMergers.overwrite;

@RunWith( Parameterized.class )
public class InternalTreeLogicTest
{
    private static final ValueMerger<MutableLong> ADDER = (base,add) -> {
        base.add( add.longValue() );
        return base;
    };

    private final int pageSize = 256;

    private final SimpleIdProvider id = new SimpleIdProvider();
    private final Layout<MutableLong,MutableLong> layout = new SimpleLongLayout();
    private final TreeNode<MutableLong,MutableLong> node = new TreeNode<>( pageSize, layout );
    private final InternalTreeLogic<MutableLong,MutableLong> treeLogic = new InternalTreeLogic<>( id, node, layout );

    private final PageAwareByteArrayCursor cursor = new PageAwareByteArrayCursor( pageSize );
    private final PageAwareByteArrayCursor readCursor = cursor.duplicate();
    private final int maxKeyCount = node.leafMaxKeyCount();

    private final MutableLong insertKey = new MutableLong();
    private final MutableLong insertValue = new MutableLong();
    private final MutableLong readKey = new MutableLong();
    private final MutableLong readValue = new MutableLong();
    private final StructurePropagation<MutableLong> structurePropagation = new StructurePropagation<>(
            layout.newKey(), layout.newKey() );

    private static long stableGen = GenSafePointer.MIN_GENERATION;
    private static long unstableGen = stableGen + 1;

    @Parameterized.Parameters( name = "{0}" )
    public static Collection<Object[]> generators()
    {
        List<Object[]> parameters = new ArrayList<>();
        // Initial state has same generation as update state
        parameters.add( new Object[]{
                "NoCheckpoint", GenerationManager.NO_OP_GEN, false} );
        // Update state in next generation
        parameters.add( new Object[]{
                "Checkpoint", GenerationManager.DEFAULT, true} );
        return parameters;
    }

    @Parameterized.Parameter( 0 )
    public String name;
    @Parameterized.Parameter( 1 )
    public GenerationManager generationManager;
    @Parameterized.Parameter( 2 )
    public boolean isCheckpointing;

    @Rule
    public RandomRule random = new RandomRule();

    private long rootId;
    private long rootGen;
    private int numberOfRootSplits;
    private int numberOfRootNewGens;

    @Before
    public void setUp() throws IOException
    {
        id.reset();
        long newId = id.acquireNewId( stableGen, unstableGen );
        goTo( cursor, newId );
        readCursor.next( newId );
    }

    @Test
    public void modifierMustInsertAtFirstPositionInEmptyLeaf() throws Exception
    {
        // given
        initialize();
        long key = 1L;
        long value = 1L;
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( 0 ) );

        // when
        generationManager.checkpoint();
        insert( key, value );

        // then
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( 1 ) );
        assertThat( keyAt(0 ), is( key ) );
        assertThat( valueAt( 0 ), is( key ) );
    }

    @Test
    public void modifierMustSortCorrectlyOnInsertFirstInLeaf() throws Exception
    {
        // given
        initialize();
        generationManager.checkpoint();
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            // when
            long key = maxKeyCount - i;
            insert( key, key );

            // then
            readCursor.next( rootId );
            assertThat( keyAt( 0 ), is( key ) );
            assertThat( valueAt( 0 ), is( key ) );
        }
    }

    @Test
    public void modifierMustSortCorrectlyOnInsertLastInLeaf() throws Exception
    {
        // given
        initialize();
        generationManager.checkpoint();
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            // when
            insert( i, i );

            // then
            readCursor.next( rootId );
            assertThat( keyAt( i ), is( (long) i ) );
            assertThat( valueAt( i ), is( (long) i ) );
        }
    }

    @Test
    public void modifierMustSortCorrectlyOnInsertInMiddleOfLeaf() throws Exception
    {
        // given
        initialize();
        generationManager.checkpoint();
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            // when
            long key = i % 2 == 0 ? i / 2 : maxKeyCount - i / 2;
            insert( key, key );

            // then
            readCursor.next( rootId );
            assertThat( keyAt( (i + 1) / 2 ), is( key ) );
        }
    }

    @Test
    public void modifierMustSplitWhenInsertingMiddleOfFullLeaf() throws Exception
    {
        // given
        initialize();
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            long key = i % 2 == 0 ? i : maxKeyCount * 2 - i;
            insert( key, key );
        }

        // when
        generationManager.checkpoint();
        long middle = maxKeyCount;
        insert( middle, middle );

        // then
        assertEquals( 1, numberOfRootSplits );
    }

    @Test
    public void modifierMustSplitWhenInsertingLastInFullLeaf() throws Exception
    {
        // given
        initialize();
        long key = 0;
        while ( key < maxKeyCount )
        {
            insert( key, key );
            assertFalse( structurePropagation.hasRightKeyInsert );
            key++;
        }

        // when
        generationManager.checkpoint();
        insert( key, key );

        // then
        assertEquals( 1, numberOfRootSplits ); // Should cause a split
    }

    @Test
    public void modifierMustSplitWhenInsertingFirstInFullLeaf() throws Exception
    {
        // given
        initialize();
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            long key = i + 1;
            insert( key, key );
            assertFalse( structurePropagation.hasRightKeyInsert );
        }

        // when
        generationManager.checkpoint();
        insert( 0L, 0L );

        // then
        assertEquals( 1, numberOfRootSplits );
    }

    @Test
    public void modifierMustUpdatePointersInSiblingsToSplit() throws Exception
    {
        // given
        initialize();
        long someLargeNumber = maxKeyCount * 1000;
        long i = 0;
        while ( i < maxKeyCount )
        {
            insert( someLargeNumber - i, i );
            i++;
        }

        // First split
        generationManager.checkpoint();
        insert( someLargeNumber - i, i );
        i++;

        // Assert child pointers and sibling pointers are intact after split in root
        goTo( readCursor, rootId );
        long child0 = childAt( readCursor, 0, stableGen, unstableGen );
        long child1 = childAt( readCursor, 1, stableGen, unstableGen );
        assertSiblingOrderAndPointers( child0, child1 );

        // Insert until we have another split in leftmost leaf
        while ( keyCount( rootId ) == 1 )
        {
            insert( someLargeNumber - i, i );
            i++;
        }

        // Just to be sure
        assertTrue( TreeNode.isInternal( readCursor ) );
        assertThat( node.keyCount( readCursor ), is( 2 ) );

        // Assert child pointers and sibling pointers are intact
        // AND that node not involved in split also has its left sibling pointer updated
        child0 = childAt( readCursor, 0, stableGen, unstableGen );
        child1 = childAt( readCursor, 1, stableGen, unstableGen );
        long child2 = childAt( readCursor, 2, stableGen, unstableGen ); // <- right sibling to split-node before split

        assertSiblingOrderAndPointers( child0, child1, child2 );
    }

    /* REMOVE */
    @Test
    public void modifierMustRemoveFirstInEmptyLeaf() throws Exception
    {
        // given
        initialize();
        long key = 1L;
        long value = 1L;
        insert( key, value );

        // when
        generationManager.checkpoint();
        remove( key, readValue );

        // then
        goTo( readCursor, rootId );
        assertThat( node.keyCount( cursor ), is( 0 ) );
    }

    @Test
    public void modifierMustRemoveFirstInFullLeaf() throws Exception
    {
        // given
        initialize();
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            insert( i, i );
        }

        // when
        generationManager.checkpoint();
        remove( 0, readValue );

        // then
        goTo( readCursor, rootId );
        assertThat( node.keyCount( readCursor ), is( maxKeyCount - 1) );
        for ( int i = 0; i < maxKeyCount - 1; i++ )
        {
            assertThat( keyAt( i ), is( i + 1L ) );
        }
    }

    @Test
    public void modifierMustRemoveInMiddleInFullLeaf() throws Exception
    {
        // given
        initialize();
        int middle = maxKeyCount / 2;
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            insert( i, i );
        }

        // when
        generationManager.checkpoint();
        remove( middle, readValue );

        // then
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( maxKeyCount - 1) );
        assertThat( keyAt( middle ), is( middle + 1L ) );
        for ( int i = 0; i < maxKeyCount - 1; i++ )
        {
            long expected = i < middle ? i : i + 1L;
            assertThat( keyAt( i ), is( expected ) );
        }
    }

    @Test
    public void modifierMustRemoveLastInFullLeaf() throws Exception
    {
        initialize();
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            insert( i, i );
        }

        // when
        generationManager.checkpoint();
        remove( maxKeyCount - 1, readValue );

        // then
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( maxKeyCount - 1) );
        for ( int i = 0; i < maxKeyCount - 1; i++ )
        {
            Long actual = keyAt( i );
            assertThat( actual, is( (long) i ) );
        }
    }

    @Test
    public void modifierMustRemoveFromLeftChild() throws Exception
    {
        initialize();
        for ( int i = 0; numberOfRootSplits == 0; i++ )
        {
            insert( i, i );
        }

        // when
        generationManager.checkpoint();
        goTo( readCursor, structurePropagation.midChild );
        assertThat( keyAt( 0 ), is( 0L ) );
        remove( 0, readValue );

        // then
        goTo( readCursor, structurePropagation.midChild );
        assertThat( keyAt( 0 ), is( 1L ) );
    }

    @Test
    public void modifierMustRemoveFromRightChildButNotFromInternalWithHitOnInternalSearch() throws Exception
    {
        initialize();
        for ( int i = 0; numberOfRootSplits == 0; i++ )
        {
            insert( i, i );
        }

        // when key to remove exists in internal
        Long keyToRemove = structurePropagation.rightKey.getValue();
        goTo( readCursor, rootId );
        assertThat( keyAt( 0 ), is( keyToRemove ) );

        // and as first key in right child
        long rightChild = structurePropagation.rightChild;
        goTo( readCursor, rightChild );
        int keyCountInRightChild = keyCount();
        assertThat( keyAt( 0 ), is( keyToRemove ) );

        // and we remove it
        generationManager.checkpoint();
        remove( keyToRemove, readValue );

        // then we should still find it in internal
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( 1 ) );
        assertThat( keyAt( 0 ), is( keyToRemove ) );

        // but not in right leaf
        rightChild = childAt( readCursor, 1, stableGen, unstableGen );
        goTo( readCursor, rightChild );
        assertThat( keyCount(), is( keyCountInRightChild - 1 ) );
        assertThat( keyAt( 0 ), is( keyToRemove + 1 ) );
    }

    @Test
    public void modifierMustNotRemoveWhenKeyDoesNotExist() throws Exception
    {
        // given
        initialize();
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            insert( i, i );
        }

        // when
        generationManager.checkpoint();
        assertNull( remove( maxKeyCount, readValue ) );

        // then
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( maxKeyCount ) );
        for ( int i = 0; i < maxKeyCount; i++ )
        {
            Long actual = keyAt( i );
            assertThat( actual, is( (long) i ) );
        }
    }

    @Test
    public void modifierMustNotRemoveWhenKeyOnlyExistInInternal() throws Exception
    {
        // given
        initialize();
        for ( int i = 0; numberOfRootSplits == 0; i++ )
        {
            insert( i, i );
        }

        // when key to remove exists in internal
        Long keyToRemove = structurePropagation.rightKey.getValue();
        assertThat( keyAt( rootId, 0 ), is( keyToRemove ) );

        // and as first key in right child
        long currentRightChild = structurePropagation.rightChild;
        goTo( readCursor, currentRightChild );
        int keyCountInRightChild = keyCount();
        assertThat( keyAt( 0 ), is( keyToRemove ) );

        // and we remove it
        generationManager.checkpoint();
        remove( keyToRemove, readValue ); // Possibly create new gen of right child
        goTo( readCursor, rootId );
        currentRightChild = childAt( readCursor, 1, stableGen, unstableGen );

        // then we should still find it in internal
        assertThat( keyCount(), is( 1 ) );
        assertThat( keyAt( 0 ), is( keyToRemove ) );

        // but not in right leaf
        goTo( readCursor, currentRightChild );
        assertThat( keyCount(), is( keyCountInRightChild - 1 ) );
        assertThat( keyAt( 0 ), is( keyToRemove + 1 ) );

        // and when we remove same key again, nothing should change
        assertNull( remove( keyToRemove, readValue ) );
    }

    /* REBALANCE (when rebalance is implemented) */
    /* MERGE (when merge is implemented) */

    @Test
    public void modifierMustProduceConsistentTreeWithRandomInserts() throws Exception
    {
        // given
        initialize();
        int numberOfEntries = 100_000;
        for ( int i = 0; i < numberOfEntries; i++ )
        {
            // when
            insert( random.nextLong(), random.nextLong() );
            if ( i == numberOfEntries / 2 )
            {
                generationManager.checkpoint();
            }
        }

        // then
        goTo( readCursor, rootId );
        ConsistencyChecker<MutableLong> consistencyChecker =
                new ConsistencyChecker<>( node, layout, stableGen, unstableGen );
        consistencyChecker.check( readCursor, rootGen );
    }

    /* TEST VALUE MERGER */

    @Test
    public void modifierMustOverwriteWithOverwriteMerger() throws Exception
    {
        // given
        initialize();
        long key = random.nextLong();
        long firstValue = random.nextLong();
        insert( key, firstValue );

        // when
        generationManager.checkpoint();
        long secondValue = random.nextLong();
        insert( key, secondValue, ValueMergers.overwrite() );

        // then
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( 1 ) );
        assertThat( valueAt( 0 ), is( secondValue ) );
    }

    @Test
    public void modifierMustKeepExistingWithKeepExistingMerger() throws Exception
    {
        // given
        initialize();
        long key = random.nextLong();
        long firstValue = random.nextLong();
        insert( key, firstValue, ValueMergers.keepExisting() );
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( 1 ) );
        Long actual = valueAt( 0 );
        assertThat( actual, is( firstValue ) );

        // when
        generationManager.checkpoint();
        long secondValue = random.nextLong();
        insert( key, secondValue, ValueMergers.keepExisting() );

        // then
        goTo( readCursor, rootId );
        assertThat( keyCount(), is( 1 ) );
        actual = valueAt( 0 );
        assertThat( actual, is( firstValue ) );
    }

    @Test
    public void shouldMergeValueInRootLeaf() throws Exception
    {
        // GIVEN
        initialize();
        long key = 10;
        long baseValue = 100;
        insert( key, baseValue );

        // WHEN
        generationManager.checkpoint();
        int toAdd = 5;
        insert( key, toAdd, ADDER );

        // THEN
        goTo( readCursor, rootId );
        int searchResult = KeySearch.search( readCursor, node, key( key ), new MutableLong(), keyCount() );
        assertTrue( KeySearch.isHit( searchResult ) );
        int pos = KeySearch.positionOf( searchResult );
        assertEquals( 0, pos );
        assertEquals( key, keyAt( pos ).longValue() );
        assertEquals( baseValue + toAdd, valueAt( pos ).longValue() );
    }

    @Test
    public void shouldMergeValueInLeafLeftOfParentKey() throws Exception
    {
        // GIVEN
        initialize();
        for ( int i = 0; numberOfRootSplits == 0; i++ )
        {
            insert( i, i );
        }

        // WHEN
        generationManager.checkpoint();
        long key = 1;
        int toAdd = 5;
        insert( key, toAdd, ADDER );

        // THEN
        goTo( readCursor, structurePropagation.midChild );
        int searchResult = KeySearch.search( readCursor, node, key( key ), new MutableLong(), keyCount() );
        assertTrue( KeySearch.isHit( searchResult ) );
        int pos = KeySearch.positionOf( searchResult );
        assertEquals( 1, pos );
        assertEquals( key, keyAt( pos ).longValue() );
        assertEquals( key + toAdd, valueAt( pos ).longValue() );
    }

    @Test
    public void shouldMergeValueInLeafAtParentKey() throws Exception
    {
        // GIVEN
        initialize();
        for ( int i = 0; numberOfRootSplits == 0; i++ )
        {
            insert( i, i );
        }

        // WHEN
        generationManager.checkpoint();
        long key = structurePropagation.rightKey.longValue();
        int toAdd = 5;
        insert( key, toAdd, ADDER );

        // THEN
        goTo( readCursor, rootId );
        long rightChild = childAt( readCursor, 1, stableGen, unstableGen );
        goTo( readCursor, rightChild );
        int searchResult = KeySearch.search( readCursor, node, key( key ), new MutableLong(), keyCount() );
        assertTrue( KeySearch.isHit( searchResult ) );
        int pos = KeySearch.positionOf( searchResult );
        assertEquals( 0, pos );
        assertEquals( key, keyAt( pos ).longValue() );
        assertEquals( key + toAdd, valueAt( pos ).longValue() );
    }

    @Test
    public void shouldMergeValueInLeafBetweenTwoParentKeys() throws Exception
    {
        // GIVEN
        initialize();
        long firstSplitPrimKey = -1;
        for ( int i = 0; numberOfRootSplits == 0 || keyCount( rootId ) < 1; i++ )
        {
            insert( i, i );
            if ( firstSplitPrimKey == -1 && numberOfRootSplits == 1 )
            {
                firstSplitPrimKey = structurePropagation.rightKey.longValue();
            }
        }

        // WHEN
        generationManager.checkpoint();
        long key = firstSplitPrimKey + 1;
        int toAdd = 5;
        insert( key, toAdd, ADDER );

        // THEN
        goTo( readCursor, rootId );
        long middle = childAt( readCursor, 1, stableGen, unstableGen );
        goTo( readCursor, middle );
        int searchResult = KeySearch.search( readCursor, node, key( key ), new MutableLong(), keyCount() );
        assertTrue( KeySearch.isHit( searchResult ) );
        int pos = KeySearch.positionOf( searchResult );
        assertEquals( 1, pos );
        assertEquals( key, keyAt( pos ).longValue() );
        assertEquals( key + toAdd, valueAt( pos ).longValue() );
    }

    @Test
    public void shouldCreateNewVersionWhenInsertInStableRootAsLeaf() throws Exception
    {
        assumeTrue( "No checkpointing, no new gen", isCheckpointing );

        // GIVEN root
        initialize();
        long oldGenId = cursor.getCurrentPageId();

        // WHEN root -[newGen]-> newGen root
        generationManager.checkpoint();
        insert( 1L, 1L );
        long newGenId = cursor.getCurrentPageId();

        // THEN
        goTo( readCursor, rootId );
        assertEquals( 1, numberOfRootNewGens );
        assertEquals( newGenId, structurePropagation.midChild );
        assertNotEquals( oldGenId, newGenId );
        assertEquals( 1, keyCount() );

        goTo( readCursor, oldGenId );
        assertEquals( newGenId, newGen( readCursor, stableGen, unstableGen ) );
        assertEquals( 0, keyCount() );
    }

    @Test
    public void shouldCreateNewVersionWhenRemoveInStableRootAsLeaf() throws Exception
    {
        assumeTrue( "No checkpointing, no new gen", isCheckpointing );

        // GIVEN root
        initialize();
        long key = 1L;
        long value = 10L;
        insert( key, value );
        long oldGenId = cursor.getCurrentPageId();

        // WHEN root -[newGen]-> newGen root
        generationManager.checkpoint();
        remove( key, readValue );
        long newGenId = cursor.getCurrentPageId();

        // THEN
        goTo( readCursor, rootId );
        assertEquals( 1, numberOfRootNewGens );
        assertEquals( newGenId, structurePropagation.midChild );
        assertNotEquals( oldGenId, newGenId );
        assertEquals( 0, keyCount() );

        goTo( readCursor, oldGenId );
        assertEquals( newGenId, newGen( readCursor, stableGen, unstableGen ) );
        assertEquals( 1, keyCount() );
    }

    @Test
    public void shouldCreateNewVersionWhenInsertInStableLeaf() throws Exception
    {
        assumeTrue( "No checkpointing, no new gen", isCheckpointing );

        // GIVEN:
        //       ------root-------
        //      /        |         \
        //     v         v          v
        //   left <--> middle <--> right
        initialize();
        long targetLastId = id.lastId() + 3; // 2 splits and 1 new allocated root
        long i = 0;
        for ( ; id.lastId() < targetLastId; i++ )
        {
            insert( i, i );
        }
        goTo( readCursor, rootId );
        assertEquals( 2, keyCount() );
        long leftChild = childAt( readCursor, 0, stableGen, unstableGen );
        long middleChild = childAt( readCursor, 1, stableGen, unstableGen );
        long rightChild = childAt( readCursor, 2, stableGen, unstableGen );
        assertSiblings( leftChild, middleChild, rightChild );

        // WHEN
        generationManager.checkpoint();
        long middleKey = i / 2; // Should be located in middle leaf
        long newValue = middleKey * 100;
        insert( middleKey, newValue );

        // THEN
        // root have new middle child
        long expectedNewMiddleChild = targetLastId + 1;
        assertEquals( expectedNewMiddleChild, id.lastId() );
        long newMiddleChild = childAt( readCursor, 1, stableGen, unstableGen );
        assertEquals( expectedNewMiddleChild, newMiddleChild );

        // old middle child has new gen
        goTo( readCursor, middleChild );
        assertEquals( newMiddleChild, newGen( readCursor, stableGen, unstableGen ) );

        // old middle child has seen no change
        assertKeyAssociatedWithValue( middleKey, middleKey );

        // new middle child has seen change
        goTo( readCursor, newMiddleChild );
        assertKeyAssociatedWithValue( middleKey, newValue );

        // sibling pointers updated
        assertSiblings( leftChild, newMiddleChild, rightChild );
    }

    @Test
    public void shouldCreateNewVersionWhenRemoveInStableLeaf() throws Exception
    {
        assumeTrue( "No checkpointing, no new gen", isCheckpointing );

        // GIVEN:
        //       ------root-------
        //      /        |         \
        //     v         v          v
        //   left <--> middle <--> right
        initialize();
        long targetLastId = id.lastId() + 3; // 2 splits and 1 new allocated root
        long i = 0;
        for ( ; id.lastId() < targetLastId; i++ )
        {
            insert( i, i );
        }
        goTo( readCursor, rootId );
        assertEquals( 2, keyCount() );
        long leftChild = childAt( readCursor, 0, stableGen, unstableGen );
        long middleChild = childAt( readCursor, 1, stableGen, unstableGen );
        long rightChild = childAt( readCursor, 2, stableGen, unstableGen );
        assertSiblings( leftChild, middleChild, rightChild );

        // WHEN
        generationManager.checkpoint();
        long middleKey = i / 2; // Should be located in middle leaf
        remove( middleKey, insertValue );

        // THEN
        // root have new middle child
        long expectedNewMiddleChild = targetLastId + 1;
        assertEquals( expectedNewMiddleChild, id.lastId() );
        long newMiddleChild = childAt( readCursor, 1, stableGen, unstableGen );
        assertEquals( expectedNewMiddleChild, newMiddleChild );

        // old middle child has new gen
        goTo( readCursor, middleChild );
        assertEquals( newMiddleChild, newGen( readCursor, stableGen, unstableGen ) );

        // old middle child has seen no change
        assertKeyAssociatedWithValue( middleKey, middleKey );

        // new middle child has seen change
        goTo( readCursor, newMiddleChild );
        assertKeyNotFound( middleKey );

        // sibling pointers updated
        assertSiblings( leftChild, newMiddleChild, rightChild );
    }

    @Test
    public void shouldCreateNewVersionWhenInsertInStableRootAsInternal() throws Exception
    {
        assumeTrue( "No checkpointing, no new gen", isCheckpointing );

        // GIVEN:
        //                       root
        //                   ----   ----
        //                  /           \
        //                 v             v
        //               left <-------> right
        initialize();
        long i = 0;
        int countToProduceAboveImageAndFullRight =
                maxKeyCount /*will split root leaf into two half left/right*/ + maxKeyCount / 2;
        for ( ; i < countToProduceAboveImageAndFullRight; i++ )
        {
            insert( i, i );
        }
        long oldRootId = rootId;
        goTo( readCursor, rootId );
        assertEquals( 1, keyCount() );
        long leftChild = childAt( readCursor, 0, stableGen, unstableGen );
        long rightChild = childAt( readCursor, 1, stableGen, unstableGen );
        assertSiblings( leftChild, rightChild, TreeNode.NO_NODE_FLAG );

        // WHEN
        //                       root(newGen)
        //                   ----  | ---------------
        //                  /      |                \
        //                 v       v                 v
        //               left <-> right(newGen) <--> farRight
        generationManager.checkpoint();
        insert( i, i );
        assertEquals( 1, numberOfRootNewGens );
        goTo( readCursor, rootId );
        leftChild = childAt( readCursor, 0, stableGen, unstableGen );
        rightChild = childAt( readCursor, 1, stableGen, unstableGen );

        // THEN
        // siblings are correct
        long farRightChild = childAt( readCursor, 2, stableGen, unstableGen );
        assertSiblings( leftChild, rightChild, farRightChild );

        // old root points to new gen root
        goTo( readCursor, oldRootId );
        assertEquals( rootId, newGen( readCursor, stableGen, unstableGen ) );
    }

    @Test
    public void shouldCreateNewVersionWhenInsertInStableInternal() throws Exception
    {
        assumeTrue( "No checkpointing, no new gen", isCheckpointing );

        // GIVEN
        initialize();
        for ( int i = 0; numberOfRootSplits < 2; i++ )
        {
            long keyAndValue = i * maxKeyCount;
            insert( keyAndValue, keyAndValue );
        }
        long rootAfterInitialData = rootId;
        goTo( readCursor, rootId );
        assertEquals( 1, keyCount() );
        long leftInternal = childAt( readCursor, 0, stableGen, unstableGen );
        long rightInternal = childAt( readCursor, 1, stableGen, unstableGen );
        assertSiblings( leftInternal, rightInternal, TreeNode.NO_NODE_FLAG );
        goTo( readCursor, leftInternal );
        int leftInternalKeyCount = keyCount();
        assertTrue( TreeNode.isInternal( readCursor ) );
        long leftLeaf = childAt( readCursor, 0, stableGen, unstableGen );
        goTo( readCursor, leftLeaf );
        long firstKeyInLeaf = keyAt(0 );

        // WHEN
        generationManager.checkpoint();
        long targetLastId = id.lastId() + 3; /*one for newGen in leaf, one for split leaf, one for newGen in internal*/
        for ( int i = 0; id.lastId() < targetLastId; i++ )
        {
            insert( firstKeyInLeaf + i, firstKeyInLeaf + i );
            assertFalse( structurePropagation.hasRightKeyInsert ); // there should be no root split
        }

        // THEN
        // root hasn't been split further
        assertEquals( rootAfterInitialData, rootId );

        // there's a new generation of left internal w/ one more key in
        goTo( readCursor, rootId );
        long newGenLeftInternal = id.lastId();
        assertEquals( newGenLeftInternal, childAt( readCursor, 0, stableGen, unstableGen ) );
        goTo( readCursor, newGenLeftInternal );
        int newGenLeftInternalKeyCount = keyCount();
        assertEquals( leftInternalKeyCount + 1, newGenLeftInternalKeyCount );

        // and left internal points to the new gen
        goTo( readCursor, leftInternal );
        assertEquals( newGenLeftInternal, newGen( readCursor, stableGen, unstableGen ) );
        assertSiblings( newGenLeftInternal, rightInternal, TreeNode.NO_NODE_FLAG );
    }

    @Test
    public void shouldOverwriteInheritedNewGenOnNewGen() throws Exception
    {
        // GIVEN
        assumeTrue( isCheckpointing );
        initialize();
        long originalNodeId = rootId;
        generationManager.checkpoint();
        insert( 1L, 10L ); // TX1 will create heir
        assertEquals( 1, numberOfRootNewGens );

        // WHEN
        // recovery happens
        generationManager.recovery();
        // start up on stable root
        goTo( cursor, originalNodeId );
        treeLogic.initialize( cursor );
        // replay transaction TX1 will create a new heir
        insert( 1L, 10L );
        assertEquals( 2, numberOfRootNewGens );

        // THEN
        goTo( readCursor, rootId );
        // new gen pointer for heir should not have broken or crashed GSPP slot
        assertNewGenPointerNotCrashOrBroken();
        // and previously crashed new gen GSPP slot should have been overwritten
        goTo( readCursor, originalNodeId );
        assertNewGenPointerNotCrashOrBroken();
    }

    private int keyCount( long nodeId ) throws IOException
    {
        long prevId = readCursor.getCurrentPageId();
        try
        {
            goTo( readCursor, nodeId );
            return node.keyCount( readCursor );
        }
        finally
        {
            goTo( readCursor, prevId );
        }
    }

    private int keyCount()
    {
        return node.keyCount( readCursor );
    }

    private void initialize()
    {
        node.initializeLeaf( cursor, stableGen, unstableGen );
        updateRoot();
    }

    private void updateRoot()
    {
        rootId = cursor.getCurrentPageId();
        rootGen = unstableGen;
        treeLogic.initialize( cursor );
    }

    private void assertNewGenPointerNotCrashOrBroken()
    {
        assertNoCrashOrBrokenPointerInGSPP( readCursor, stableGen, unstableGen, "NewGen", TreeNode.BYTE_POS_NEWGEN, node );
    }

    private void assertKeyAssociatedWithValue( long key, long expectedValue )
    {
        insertKey.setValue( key );
        int search = KeySearch.search( readCursor, node, insertKey, readKey, node.keyCount( readCursor ) );
        assertTrue( KeySearch.isHit( search ) );
        int keyPos = KeySearch.positionOf( search );
        node.valueAt( readCursor, readValue, keyPos );
        assertEquals( expectedValue, readValue.longValue() );
    }

    private void assertKeyNotFound( long key )
    {
        insertKey.setValue( key );
        int search = KeySearch.search( readCursor, node, insertKey, readKey, node.keyCount( readCursor ) );
        assertFalse( KeySearch.isHit( search ) );
    }

    private void assertSiblings( long left, long middle, long right ) throws IOException
    {
        long origin = readCursor.getCurrentPageId();
        goTo( readCursor, middle );
        assertEquals( right, rightSibling( readCursor, stableGen, unstableGen ) );
        assertEquals( left, leftSibling( readCursor, stableGen, unstableGen ) );
        if ( left != TreeNode.NO_NODE_FLAG )
        {
            goTo( readCursor, left );
            assertEquals( middle, rightSibling( readCursor, stableGen, unstableGen ) );
        }
        if ( right != TreeNode.NO_NODE_FLAG )
        {
            goTo( readCursor, right );
            assertEquals( middle, leftSibling( readCursor, stableGen, unstableGen ) );
        }
        goTo( readCursor, origin );
    }

    // KEEP even if unused
    private void printTree() throws IOException
    {
        new TreePrinter<>( node, layout, stableGen, unstableGen ).printTree( cursor, System.out, true, true );
    }

    private static MutableLong key( long key )
    {
        return new MutableLong( key );
    }

    private void newRootFromSplit( StructurePropagation<MutableLong> split ) throws IOException
    {
        assertTrue( split.hasRightKeyInsert );
        long rootId = id.acquireNewId( stableGen, unstableGen );
        goTo( cursor, rootId );
        node.initializeInternal( cursor, stableGen, unstableGen );
        node.insertKeyAt( cursor, split.rightKey, 0, 0 );
        node.setKeyCount( cursor, 1 );
        node.setChildAt( cursor, split.midChild, 0, stableGen, unstableGen );
        node.setChildAt( cursor, split.rightChild, 1, stableGen, unstableGen );
        split.hasRightKeyInsert = false;
        updateRoot();
    }

    private void assertSiblingOrderAndPointers( long... children ) throws IOException
    {
        long currentPageId = readCursor.getCurrentPageId();
        RightmostInChain rightmost = new RightmostInChain();
        for ( long child : children )
        {
            goTo( readCursor, child );
            long leftSibling = node.leftSibling( readCursor, stableGen, unstableGen );
            long rightSibling = node.rightSibling( readCursor, stableGen, unstableGen );
            rightmost.assertNext( readCursor,
                    node.gen( readCursor ),
                    pointer( leftSibling ),
                    node.pointerGen( readCursor, leftSibling ),
                    pointer( rightSibling ),
                    node.pointerGen( readCursor, rightSibling ) );
        }
        rightmost.assertLast();
        goTo( readCursor, currentPageId );
    }

    private Long keyAt( long nodeId, int pos ) throws IOException
    {
        long prevId = readCursor.getCurrentPageId();
        try
        {
            readCursor.next( nodeId );
            return node.keyAt( readCursor, readKey, pos ).getValue();
        }
        finally
        {
            readCursor.next( prevId );
        }
    }

    private Long keyAt( int pos ) throws IOException
    {
        return node.keyAt( readCursor, readKey, pos ).getValue();
    }

    private Long valueAt( int pos )
    {
        return node.valueAt( readCursor, readValue, pos ).getValue();
    }

    private void insert( long key, long value ) throws IOException
    {
        insert( key, value, overwrite() );
    }

    private void insert( long key, long value, ValueMerger<MutableLong> valueMerger ) throws IOException
    {
        structurePropagation.hasRightKeyInsert = false;
        structurePropagation.hasMidChildUpdate = false;
        insertKey.setValue( key );
        insertValue.setValue( value );
        treeLogic.insert( cursor, structurePropagation, insertKey, insertValue, valueMerger, stableGen, unstableGen );
        handleAfterChange();
    }

    private void handleAfterChange() throws IOException
    {
        if ( structurePropagation.hasRightKeyInsert )
        {
            newRootFromSplit( structurePropagation );
            numberOfRootSplits++;
        }
        if ( structurePropagation.hasMidChildUpdate )
        {
            structurePropagation.hasMidChildUpdate = false;
            updateRoot();
            numberOfRootNewGens++;
        }
    }

    private MutableLong remove( long key, MutableLong into ) throws IOException
    {
        insertKey.setValue( key );
        MutableLong result = treeLogic.remove( cursor, structurePropagation, insertKey, into, stableGen, unstableGen );
        handleAfterChange();
        return result;
    }

    private interface GenerationManager
    {
        void checkpoint();

        void recovery();

        static GenerationManager NO_OP_GEN = new GenerationManager()
        {
            @Override
            public void checkpoint()
            {
                // Do nothing
            }

            @Override
            public void recovery()
            {
                // Do nothing
            }
        };

        static GenerationManager DEFAULT = new GenerationManager()
        {
            @Override
            public void checkpoint()
            {
                stableGen = unstableGen;
                unstableGen++;
            }

            @Override
            public void recovery()
            {
                unstableGen++;
            }
        };
    }

    private static void goTo( PageCursor cursor, long pageId ) throws IOException
    {
        PageCursorUtil.goTo( cursor, "test", pointer( pageId ) );
    }

    private long childAt( PageCursor cursor, int pos, long stableGen, long unstableGen )
    {
        return pointer( node.childAt( cursor, pos, stableGen, unstableGen ) );
    }

    private long rightSibling( PageCursor cursor, long stableGen, long unstableGen )
    {
        return pointer( node.rightSibling( cursor, stableGen, unstableGen ) );
    }

    private long leftSibling( PageCursor cursor, long stableGen, long unstableGen )
    {
        return pointer( node.leftSibling( cursor, stableGen, unstableGen ) );
    }

    private long newGen( PageCursor cursor, long stableGen, long unstableGen )
    {
        return pointer( node.newGen( cursor, stableGen, unstableGen ) );
    }
}
