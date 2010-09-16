package org.lilycms.indexer.admin.cli;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.io.IOUtils;
import org.lilycms.indexer.model.api.IndexDefinition;

import java.io.OutputStream;
import java.util.List;

public class GetIndexerConfCli extends BaseIndexerAdminCli {
    @Override
    protected String getCmdName() {
        return "lily-get-indexerconf";
    }

    public static void main(String[] args) {
        new GetIndexerConfCli().start(args);
    }

    @Override
    public List<Option> getOptions() {
        List<Option> options = super.getOptions();

        nameOption.setRequired(true);

        options.add(nameOption);
        options.add(outputFileOption);
        options.add(forceOption);

        return options;
    }

    @Override
    public int run(CommandLine cmd) throws Exception {
        int result = super.run(cmd);
        if (result != 0)
            return result;

        IndexDefinition index = model.getIndex(indexName);

        byte[] conf = index.getConfiguration();

        OutputStream os = getOutput();
        IOUtils.write(conf, os);
        os.close();

        return 0;
    }
}
