/*
 *
 * Copyright 2018 Odysseus Data Services, inc.
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
 *
 * Company: Odysseus Data Services, Inc.
 * Product Owner/Architecture: Gregory Klebanov
 * Authors: Pavel Grafkin, Alexandr Ryabokon, Vitaly Koulakov, Anton Gackovka, Maria Pozhidaeva, Mikhail Mironov
 * Created: September 08, 2017
 *
 */

package com.odysseusinc.arachne.portal.service.study.impl;

import com.odysseusinc.arachne.portal.model.IDataSource;
import com.odysseusinc.arachne.portal.service.study.AddDataSourceStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class AddDataSourceStrategyFactoryImpl extends BaseAddDataSourceStrategyFactory<IDataSource> {

    private final AddPublicDataSourceStrategy<IDataSource> publicDataSourceStrategy;
    private final AddRestrictedDataSourceStrategy rectrictedDataSourceStrategy;

    @Autowired
    public AddDataSourceStrategyFactoryImpl(AddPublicDataSourceStrategy<IDataSource> addPublicDataSourceStrategy,
                                            AddRestrictedDataSourceStrategy rectrictedDataSourceStrategy) {

        this.rectrictedDataSourceStrategy = rectrictedDataSourceStrategy;
        publicDataSourceStrategy = addPublicDataSourceStrategy;
    }

    @Override
    public AddDataSourceStrategy<IDataSource> getStrategy(IDataSource dataSource) {

        AddDataSourceStrategy<IDataSource> strategy;
        switch (dataSource.getAccessType()) {
            case PUBLIC:
                strategy = publicDataSourceStrategy;
                break;
            case RESTRICTED:
                strategy = rectrictedDataSourceStrategy;
                break;
            default:
                throw new IllegalArgumentException("Unknown access type");
        }
        return strategy;
    }
}
