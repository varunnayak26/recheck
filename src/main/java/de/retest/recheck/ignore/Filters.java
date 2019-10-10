package de.retest.recheck.ignore;

import static de.retest.recheck.ignore.SearchFilterFiles.FILTER_JS_EXTENSION;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.retest.recheck.review.ignore.io.Loaders;

public class Filters {

	public static Filter load( final Path path ) throws IOException {
		if ( path.toString().toLowerCase().endsWith( FILTER_JS_EXTENSION ) ) {
			return new JSFilterImpl( path );
		}
		try ( final Stream<String> filterFileLines = Files.lines( path ) ) {
			return parse( filterFileLines );
		}
	}

	public static Filter parse( final String line ) {
		return parse( Stream.of( line ) );
	}

	public static Filter parse( final List<String> lines ) {
		return parse( lines.stream() );
	}

	public static Filter parse( final Stream<String> lines ) {
		return Loaders.load( lines ) //
				.filter( Filter.class::isInstance ) //
				.map( Filter.class::cast ) //
				.collect( Collectors.collectingAndThen( Collectors.toList(), CompoundFilter::new ) );
	}
}
