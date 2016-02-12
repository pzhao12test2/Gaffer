/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gaffer.accumulostore.operation.handler;

import gaffer.accumulostore.AccumuloStore;
import gaffer.accumulostore.key.exception.IteratorSettingException;
import gaffer.accumulostore.operation.impl.GetElementsWithinSet;
import gaffer.accumulostore.retriever.AccumuloRetriever;
import gaffer.accumulostore.retriever.impl.AccumuloIDWithinSetRetriever;
import gaffer.data.element.Element;
import gaffer.operation.OperationException;
import gaffer.store.Store;
import gaffer.store.StoreException;
import gaffer.store.operation.handler.OperationHandler;

public class GetElementsWithinSetHandler implements OperationHandler<GetElementsWithinSet<Element>, Iterable<Element>> {

    @Override
    public Iterable<Element> doOperation(final GetElementsWithinSet<Element> operation, final Store store) throws OperationException {
        return doOperation(operation, (AccumuloStore) store);
    }

    public Iterable<Element> doOperation(final GetElementsWithinSet<Element> operation, final AccumuloStore store) {
        final AccumuloRetriever<?> ret;
        try {
            if (operation.isSummarise()) {
                ret = new AccumuloIDWithinSetRetriever(store, operation,
                        store.getKeyPackage().getIteratorFactory().getQueryTimeAggregatorIteratorSetting(store));
            } else {
                ret = new AccumuloIDWithinSetRetriever(store, operation);
            }
        } catch (IteratorSettingException | StoreException e) {
            throw new RuntimeException(e);
        }
        return ret;
    }

}
