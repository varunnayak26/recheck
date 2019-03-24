package de.retest.recheck.review.workers;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import de.retest.recheck.ignore.JSShouldIgnoreImpl;
import de.retest.recheck.ignore.RecheckIgnoreUtil;
import de.retest.recheck.ignore.ShouldIgnore;
import de.retest.recheck.review.GlobalIgnoreApplier;
import de.retest.recheck.review.GlobalIgnoreApplier.PersistableGlobalIgnoreApplier;
import de.retest.recheck.review.ignore.io.Loaders;

public class SaveShouldIgnoreWorker {

	private final GlobalIgnoreApplier applier;

	public SaveShouldIgnoreWorker( final GlobalIgnoreApplier applier ) {
		this.applier = applier;
	}

	public void save() throws IOException {
		final Optional<Path> ignoreFile = RecheckIgnoreUtil.getIgnoreFile();
		final PersistableGlobalIgnoreApplier persist = applier.persist();

		// Filter JSShouldIgnore because that would create unnecessary file content.
		final Stream<ShouldIgnore> ignores = persist.getIgnores().stream() //
				.filter( ((Predicate<ShouldIgnore>) JSShouldIgnoreImpl.class::isInstance).negate() );
		final Stream<String> save = Loaders.save( ignores );

		try ( final PrintStream writer = new PrintStream( Files.newOutputStream(
				ignoreFile.orElseThrow( () -> new IllegalArgumentException( "No recheck.ignore found." ) ) ) ) ) {
			save.forEach( writer::println );
		}
	}
}
