package de.retest.recheck;

import static de.retest.recheck.Properties.TEST_REPORT_FILE_EXTENSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.support.membermodification.MemberMatcher.method;

import java.awt.HeadlessException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import de.retest.recheck.ignore.Filter;
import de.retest.recheck.persistence.FileNamer;
import de.retest.recheck.ui.DefaultValueFinder;
import de.retest.recheck.ui.descriptors.IdentifyingAttributes;
import de.retest.recheck.ui.descriptors.RootElement;

@RunWith( PowerMockRunner.class )
@PrepareForTest( Rehub.class )
public class RecheckImplTest {

	@Rule
	TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void using_strange_stepText_should_be_normalized() throws Exception {
		final FileNamerStrategy fileNamerStrategy = spy( new MavenConformFileNamerStrategy() );
		final RecheckOptions opts = RecheckOptions.builder() //
				.fileNamerStrategy( fileNamerStrategy ) //
				.setFilter( Filter.FILTER_NOTHING ) // Suppress IllegalStateException: No JavaScript available.
				.build();
		final Recheck cut = new RecheckImpl( opts );
		final RecheckAdapter adapter = mock( RecheckAdapter.class );

		try {
			cut.check( mock( Object.class ), adapter, "!@#%$^&)te}{:|\\\":xt!(@*$" );
		} catch ( final Exception e ) {
			// Ignore Exceptions, fear AssertionErrors...
		}

		verify( fileNamerStrategy ).createFileNamer( eq( fileNamerStrategy.getTestClassName() ) );
		verify( fileNamerStrategy ).createFileNamer( endsWith( ".!@#_$^&)te}{_____xt!(@_$" ) );
	}

	@Test
	public void test_class_name_should_be_default_result_file_name() throws Exception {
		final String suiteName = getClass().getName();
		final RecheckImpl cut = new RecheckImpl();
		final String resultFileName = cut.getResultFile().getName();
		assertThat( resultFileName ).isEqualTo( suiteName + TEST_REPORT_FILE_EXTENSION );
	}

	@Test
	public void exec_suite_name_should_be_used_for_result_file_name() throws Exception {
		final String suiteName = "FooBar";
		final RecheckOptions opts = RecheckOptions.builder() //
				.suiteName( suiteName ) //
				.setFilter( Filter.FILTER_NOTHING ) // Suppress IllegalStateException: No JavaScript available.
				.build();
		final RecheckImpl cut = new RecheckImpl( opts );
		final String resultFileName = cut.getResultFile().getName();
		assertThat( resultFileName ).isEqualTo( suiteName + TEST_REPORT_FILE_EXTENSION );
	}

	@Test
	public void calling_check_without_startTest_should_work() throws Exception {
		final Path root = temp.newFolder().toPath();
		final RecheckOptions opts = RecheckOptions.builder() //
				.fileNamerStrategy( new WithinTempDirectoryFileNamerStrategy( root ) ) //
				.setFilter( Filter.FILTER_NOTHING ) // Suppress IllegalStateException: No JavaScript available.
				.build();
		final Recheck cut = new RecheckImpl( opts );
		cut.check( "String", new DummyStringRecheckAdapter(), "step" );
	}

	@Test
	public void calling_with_no_GM_should_produce_better_error_msg() throws Exception {
		final Path root = temp.newFolder().toPath();
		final RecheckOptions opts = RecheckOptions.builder() //
				.fileNamerStrategy( new WithinTempDirectoryFileNamerStrategy( root ) ) //
				.setFilter( Filter.FILTER_NOTHING ) // Suppress IllegalStateException: No JavaScript available.
				.build();
		final Recheck cut = new RecheckImpl( opts );

		final RootElement rootElement = mock( RootElement.class );
		when( rootElement.getIdentifyingAttributes() ).thenReturn( mock( IdentifyingAttributes.class ) );

		final RecheckAdapter adapter = mock( RecheckAdapter.class );
		when( adapter.canCheck( any() ) ).thenReturn( true );
		when( adapter.convert( any() ) ).thenReturn( Collections.singleton( rootElement ) );

		cut.startTest( "some-test" );
		cut.check( "to-verify", adapter, "some-step" );

		final String goldenMasterName = "SomeTestClass/some-test.some-step.recheck";
		assertThatThrownBy( cut::capTest ) //
				.isExactlyInstanceOf( AssertionError.class ) //
				.hasMessageStartingWith( "'SomeTestClass': " + NoGoldenMasterActionReplayResult.MSG_LONG ) //
				.hasMessageEndingWith( goldenMasterName );
	}

	@Test
	public void headless_no_key_should_result_in_AssertionError() throws Exception {
		final RecheckOptions opts = RecheckOptions.builder() //
				.enableReportUpload() //
				.setFilter( Filter.FILTER_NOTHING ) // Suppress IllegalStateException: No JavaScript available.
				.build();
		mockStatic( Rehub.class );
		doThrow( new HeadlessException() ).when( Rehub.class, method( Rehub.class, "init" ) ).withNoArguments();
		assertThatThrownBy( () -> new RecheckImpl( opts ) ).isExactlyInstanceOf( AssertionError.class );
	}

	private static class DummyStringRecheckAdapter implements RecheckAdapter {

		@Override
		public DefaultValueFinder getDefaultValueFinder() {
			return null;
		}

		@Override
		public Set<RootElement> convert( final Object arg0 ) {
			final IdentifyingAttributes identifyingAttributes = mock( IdentifyingAttributes.class );
			final RootElement rootElement = mock( RootElement.class );
			when( rootElement.getIdentifyingAttributes() ).thenReturn( identifyingAttributes );
			return Collections.singleton( rootElement );
		}

		@Override
		public boolean canCheck( final Object arg0 ) {
			return false;
		}
	}

	private static class WithinTempDirectoryFileNamerStrategy implements FileNamerStrategy {

		private final Path root;

		public WithinTempDirectoryFileNamerStrategy( final Path root ) throws IOException {
			this.root = root;
		}

		@Override
		public FileNamer createFileNamer( final String... baseNames ) {
			return new FileNamer() {

				@Override
				public File getFile( final String extension ) {
					return resolveRoot( baseNames, extension );
				}

				@Override
				public File getResultFile( final String extension ) {
					return resolveRoot( baseNames, extension );
				}
			};
		}

		private File resolveRoot( final String[] baseNames, final String extension ) {
			final int last = baseNames.length - 1;
			final List<String> list = new ArrayList<>( Arrays.asList( baseNames ) );
			list.set( last, baseNames[last] + extension );

			Path path = root;
			for ( final String sub : list ) {
				path = path.resolve( sub );
			}

			return path.toFile();
		}

		@Override
		public String getTestClassName() {
			return "SomeTestClass";
		}

		@Override
		public String getTestMethodName() {
			return "someTestMethod";
		}
	}
}
