import SwiftUI

@main
struct YuNianApp: App {

    @StateObject private var environment = AppEnvironment.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(environment)
                .task {
                    environment.boot()
                }
        }
    }
}
