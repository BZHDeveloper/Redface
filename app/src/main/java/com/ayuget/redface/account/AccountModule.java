/*
 * Copyright 2015 Ayuget
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

package com.ayuget.redface.account;

import android.accounts.AccountManager;

import com.ayuget.redface.ContextModule;
import com.ayuget.redface.settings.RedfaceSettings;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module(
        includes = ContextModule.class,
        injects = {
                RedfaceUserManager.class
        },
        library = true
)
public class AccountModule {
    @Provides @Singleton
    RedfaceAccountManager provideAccountManager(AccountManager androidAccountManager) {
        return new RedfaceAccountManager(androidAccountManager);
    }

    @Provides @Singleton
    UserManager provideUserManager(RedfaceSettings settings, RedfaceAccountManager accountManager) {
        return new RedfaceUserManager(settings, accountManager);
    }
}
