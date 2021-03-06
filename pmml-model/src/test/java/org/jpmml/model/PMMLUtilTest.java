/*
 * Copyright (c) 2016 Villu Ruusmann
 */
package org.jpmml.model;

import java.io.InputStream;

import org.dmg.pmml.PMML;
import org.dmg.pmml.Version;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PMMLUtilTest {

	@Test
	public void unmarshal() throws Exception {
		Version[] versions = Version.values();

		for(Version version : versions){
			PMML pmml;

			try(InputStream is = ResourceUtil.getStream(version)){
				pmml = PMMLUtil.unmarshal(is);
			}

			assertEquals(pmml.getVersion(), Version.PMML_4_3.getVersion());
			assertEquals(pmml.getBaseVersion(), version.getVersion());
		}
	}
}