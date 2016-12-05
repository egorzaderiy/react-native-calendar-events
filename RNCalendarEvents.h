#import "RCTBridgeModule.h"
#import "RCTEventDispatcher.h"
#import <EventKitUI/EventKitUI.h>

@interface RNCalendarEvents : NSObject <RCTBridgeModule, EKEventEditViewDelegate>
@end
