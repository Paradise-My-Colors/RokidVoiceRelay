<page>
  <view class="page">
    <view class="header"><text class="title">{{title}}</text><text class="count">{{counter}}</text></view>
    <text class="detail">{{detail}}</text>
    <view class="choices">
      <view wx:for="{{rows}}" wx:key="id" class="choice {{item.selected ? 'chosen' : ''}}" data-index="{{item.index}}" bindtap="tapRow">
        <text class="marker">{{item.selected ? '›' : ' '}}</text><text class="label">{{item.label}}</text>
      </view>
    </view>
    <text class="banner">{{banner}}</text>
    <view class="footer"><button class="back" bindtap="tapBack">Back</button><text class="hint">{{hint}}</text></view>
  </view>
</page>
<style>
.page { width: 100%; height: 100%; box-sizing: border-box; padding: 12px 16px; background-color: #000000; color: #b8ffcc; display: flex; flex-direction: column; gap: 6px; }
.header { display: flex; justify-content: space-between; align-items: center; height: 32px; }
.title { font-size: 23px; font-weight: 500; color: #00ff66; }
.count { font-size: 14px; color: #b8ffcc; }
.detail { height: 68px; font-size: 17px; line-height: 22px; }
.choices { display: flex; flex-direction: column; height: 138px; gap: 3px; }
.choice { height: 42px; padding: 6px 8px; box-sizing: border-box; display: flex; align-items: center; border: 1px solid #003d18; border-radius: 4px; }
.chosen { border: 2px solid #00ff66; background-color: #001e0c; }
.marker { width: 20px; font-size: 24px; color: #00ff66; }
.label { font-size: 19px; color: #b8ffcc; }
.banner { height: 20px; font-size: 14px; color: #b8ffcc; }
.footer { display: flex; align-items: center; gap: 8px; }
.back { height: 30px; padding: 2px 10px; font-size: 14px; color: #b8ffcc; background-color: #000000; border: 1px solid #007a30; border-radius: 4px; }
.hint { font-size: 13px; color: #b8ffcc; }
</style>
